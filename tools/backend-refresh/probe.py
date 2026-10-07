#!/usr/bin/env python3
"""Snapshot official branch heads. No upstream code is executed and no build is started."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import re

from common import PROJECT, baseline, capture, load_config, write_json


def probe(project, resolve=None):
    config = load_config(project / 'tools/backend-refresh/targets.json')
    manifests = baseline(project, config)
    heads = {}
    for name, target in config['backends'].items():
        url = 'https://github.com/' + target['repository'] + '.git'
        ref = 'refs/heads/' + target['branch']
        answer = resolve(url, ref) if resolve else capture(['git', 'ls-remote', '--exit-code', url, ref])
        fields = answer.split()
        if len(fields) != 2 or fields[1] != ref or not re.fullmatch(r'[0-9a-f]{40}', fields[0]):
            raise ValueError(f'Could not resolve the official branch head: {name}')
        heads[name] = fields[0]
    return {'schema': 1, 'app_base': capture(['git', 'rev-parse', 'HEAD'], project),
            'created_utc': datetime.now(timezone.utc).isoformat(), 'heads': heads,
            'changed': [n for n, sha in heads.items()
                        if needs_rebuild(config['backends'][n], sha, manifests[n])]}


def needs_rebuild(target, upstream_sha, manifest):
    # A reviewed Samsung patch update needs a native rebuild even if upstream
    # has not moved. Do not silently keep the binaries built with the old patch.
    return (upstream_sha != target['commit']
            or target['compat_sha256'] != manifest['rebased_patch']['sha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    snapshot = probe(PROJECT)
    write_json(args.output, snapshot)
    changed = bool(snapshot['changed'])
    print('Changed backends:', ', '.join(snapshot['changed']) if changed else 'none')
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with open(output, 'a') as stream:
            stream.write(f'changed={str(changed).lower()}\nbase={snapshot["app_base"]}\n')


if __name__ == '__main__':
    main()
