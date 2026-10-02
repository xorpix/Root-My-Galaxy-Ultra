#!/usr/bin/env python3
"""Run Gradle with this workspace's Android toolchain and current network settings."""
from pathlib import Path
import os, subprocess, sys
from urllib.parse import urlparse

toolchain = Path(sys.argv[1]).resolve()
project = Path(__file__).resolve().parents[1]
cache = toolchain / 'gradle-cache'
cache.mkdir(parents=True, exist_ok=True)
settings = ['org.gradle.daemon=false', 'org.gradle.workers.max=2',
    'systemProp.org.gradle.internal.http.connectionTimeout=60000',
    'systemProp.org.gradle.internal.http.socketTimeout=180000']
if Path('/etc/ssl/certs/java/cacerts').exists():
    settings.append('systemProp.javax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts')
proxy = urlparse(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy', '')))
if proxy.hostname:
    for protocol in ('http', 'https'):
        settings += [f'systemProp.{protocol}.proxyHost={proxy.hostname}',
                     f'systemProp.{protocol}.proxyPort={proxy.port or 80}']
(cache / 'gradle.properties').write_text('\n'.join(settings)+'\n')
(project / 'local.properties').write_text('sdk.dir='+str(toolchain / 'sdk')+'\n')
env = os.environ.copy()
env.update(JAVA_HOME=str(toolchain/'jdk21'), GRADLE_USER_HOME=str(cache),
    ANDROID_HOME=str(toolchain/'sdk'), PATH=str(toolchain/'jdk21/bin')+os.pathsep+env['PATH'])
sys.exit(subprocess.call(['bash', str(project/'gradlew'), '-p', str(project), *sys.argv[2:]], env=env))
