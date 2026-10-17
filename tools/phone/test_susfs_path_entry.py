#!/usr/bin/env python3
"""Read-only Test4 phone check; run as ordinary Termux Python, without su."""
import errno
import os
import re
import subprocess


def status(label):
    output = subprocess.check_output(
        ['su', '-c', 'head -n 3 /proc/susfs_path'], text=True, timeout=20)
    print(label + '\n' + output, end='', flush=True)
    if 'entry_diag: rev=4 ' not in output or 'syscall_armed=2 ' not in output:
        raise SystemExit('Test4 module is not loaded; no listing test was run.')
    return {name: int(value) for name, value in re.findall(r'\b(\w+)=(\d+)', output)}


def main():
    if os.geteuid() == 0:
        raise SystemExit('Run without su; only the status reader needs root.')
    before = status('BEFORE')
    sightings = 0
    for _ in range(200):
        sightings += 'susfs_path' in os.listdir('/proc')
    print('Listed:', bool(sightings), f'({sightings}/200)', flush=True)
    outcomes = {}
    for name in ('O_PATH', 'readlink'):
        try:
            if name == 'O_PATH':
                descriptor = os.open('/proc/susfs_path', os.O_PATH)
                os.close(descriptor)
            else:
                os.readlink('/proc/susfs_path')
        except OSError as error:
            outcomes[name] = error.errno
        else:
            outcomes[name] = 0
        print(name, outcomes[name], flush=True)
    after = status('AFTER')
    deltas = {name: after.get(name, 0) - before.get(name, 0)
              for name in ('sys64', 'proc_iterate', 'hidden', 'opath_enoent', 'readlink_enoent')}
    print('Delta:', deltas, flush=True)
    passed = (sightings == 0 and all(code == errno.ENOENT for code in outcomes.values())
              and all(delta > 0 for delta in deltas.values()))
    print('PASS' if passed else 'FAIL — send the complete output.', flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
