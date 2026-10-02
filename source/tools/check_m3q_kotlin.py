#!/usr/bin/env python3
"""Compile/run focused JVM checks with Kotlin compiler jars supplied by the caller.

Usage: python tools/check_m3q_kotlin.py /path/to/compiler-jars
Uses test-only Android/transport stubs. Does not assemble an APK or execute ARM64.
"""
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile

project = Path(__file__).resolve().parents[1]
java = project / 'app/src/main/java/dev/busung/s25uroot'
fixtures = project / 'tools/host_validation'
jars = list(Path(sys.argv[1]).resolve().glob('*.jar'))
if not jars:
    raise SystemExit('No Kotlin compiler/runtime jars supplied')
classpath = os.pathsep.join(map(str, jars))
with tempfile.TemporaryDirectory(prefix='m3q-kotlin-') as work:
    work = Path(work)
    # Compile the production parsers unchanged, apart from Android-dependent
    # neighbours outside this focused host fixture.
    runtime = (java / 'KernelSuRuntime.kt').read_text()
    controls = runtime[runtime.index('internal data class KernelSuControl('):runtime.index('/**\n * The proof set')]
    catalog = (java / 'AzhlCatalog.kt').read_text()
    controls += catalog[catalog.index('internal fun verifiedM3qControl('):]
    constants = re.findall(r'    const val (M3Q_(?:ROOT_SIZE|PAYLOAD_SIZE|PAYLOAD_SHA256)) = (.+)', catalog)
    controls += '\ninternal object AzhlCatalog {\n' + '\n'.join(f'    const val {k} = {v}' for k, v in constants) + '\n}\n'
    shizuku = (java / 'ShizukuController.kt').read_text()
    controls += next(line for line in shizuku.splitlines() if line.startswith('internal fun shellQuote')) + '\n'
    generated = work / 'ControlSource.kt'
    generated.write_text('package dev.busung.s25uroot\n' + controls)
    sources = [java / name for name in ('M3qLaunch.kt', 'M3qBootGuard.kt', 'M3qRunner.kt', 'RunLimits.kt')]
    sources += list(fixtures.glob('*.kt')) + [generated]
    output = work / 'checks.jar'
    command = ['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
               '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
               '-d', str(output)] + list(map(str, sources))
    subprocess.run(command, check=True)
    subprocess.run(['java', '-cp', str(output) + os.pathsep + classpath,
                    'dev.busung.s25uroot.RunnerChecksKt', str(project)], check=True)
