#!/usr/bin/env python3
"""One-time, lease-protected cleanup of the explicitly reviewed branch list."""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

PROTECTED = {'main', 'dev'}

def git(*args):
    result = subprocess.run(['git', *args], check=True, text=True, capture_output=True)
    return result.stdout.strip()

def remote_refs():
    return {ref: sha for line in git('ls-remote', '--refs', '--heads', '--tags', 'origin').splitlines()
            for sha, ref in [line.split()]}

def perform(plan, open_heads, dry_run=False):
    if plan['schema'] != 1 or plan['keep'] != sorted(PROTECTED):
        raise ValueError('Unexpected cleanup plan or protected branches')
    branches = plan['branches']
    names = [b['name'] for b in branches]
    if not names or len(names) != len(set(names)) or set(names) & PROTECTED:
        raise ValueError('Duplicate, empty, or protected branch selection')
    if set(names) & set(open_heads):
        raise ValueError('A selected branch still has an open pull request')
    prefix = plan['archivePrefix']
    if not re.fullmatch(r'archive/\d{4}-\d{2}-\d{2}/', prefix):
        raise ValueError('Unexpected archive namespace')
    refs = remote_refs()
    if not all('refs/heads/' + b in refs for b in PROTECTED):
        raise ValueError('Both protected branches must exist')
    report = {'schema': 1, 'repository': plan['repository'], 'status': 'planned',
              'deleted': [], 'alreadyAbsent': [], 'archived': {}}
    leases, deletions, archives = [], [], []
    for b in branches:
        name, expected = b['name'], b['sha']
        ref = 'refs/heads/' + name
        git('check-ref-format', ref)
        if not re.fullmatch(r'[0-9a-f]{40}', expected):
            raise ValueError('Invalid expected commit')
        actual = refs.get(ref)
        if actual is None:
            report['alreadyAbsent'].append(name)
            continue
        if actual != expected:
            raise ValueError('Branch advanced; preserving it: ' + name)
        git('cat-file', '-e', expected + '^{commit}')
        ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', expected, 'HEAD']).returncode
        if ancestor not in (0, 1):
            raise ValueError('Cannot establish ancestry: ' + name)
        if ancestor == 1:
            tag = 'refs/tags/' + prefix + name
            git('check-ref-format', tag)
            existing = refs.get(tag)
            if existing is not None and existing != expected:
                raise ValueError('Archive tag already identifies different work: ' + name)
            report['archived'][name] = {'ref': tag, 'sha': expected}
            if existing is None:
                archives.append((tag, expected))
                leases.append('--force-with-lease=' + tag + ':')
        leases.append('--force-with-lease=' + ref + ':' + expected)
        deletions.append(':' + ref)
        report['deleted'].append(name)
    if dry_run:
        return report
    for tag, sha in archives:
        git('update-ref', tag, sha)
    if deletions or archives:
        command = ['git', 'push', '--atomic', *leases, 'origin',
                   *[tag + ':' + tag for tag, sha in archives], *deletions]
        result = subprocess.run(command, check=True, text=True, capture_output=True)
        print(result.stdout + result.stderr, flush=True)
    after = remote_refs()
    if any('refs/heads/' + name in after for name in report['deleted']):
        raise ValueError('A selected branch remains after cleanup')
    if not all('refs/heads/' + b in after for b in PROTECTED):
        raise ValueError('Protected branch verification failed')
    for record in report['archived'].values():
        if after.get(record['ref']) != record['sha']:
            raise ValueError('Archive verification failed')
    report.update(status='complete', remainingBranches=sorted(ref.removeprefix('refs/heads/')
                  for ref in after if ref.startswith('refs/heads/')))
    return report

def open_pull_heads(repository):
    heads = set()
    for page in range(1, 11):
        request = urllib.request.Request(
            f'https://api.github.com/repos/{repository}/pulls?state=open&per_page=100&page={page}',
            headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                     'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(request, timeout=30) as response:
            pulls = json.load(response)
        heads.update(p['head']['ref'] for p in pulls
                     if (p['head'].get('repo') or {}).get('full_name') == repository)
        if len(pulls) < 100:
            return heads
    raise ValueError('Open pull-request list exceeds the reviewed limit')

def main():
    repository = 'xorpix/Root-My-Galaxy-Ultra'
    if (os.environ.get('GITHUB_REPOSITORY') != repository or
            os.environ.get('GITHUB_REF') != 'refs/heads/main'):
        raise ValueError('Run only in the authorized repository on main')
    if git('rev-parse', 'HEAD') != os.environ['GITHUB_SHA']:
        raise ValueError('Checkout differs from the workflow revision')
    plan = json.loads(Path('docs/maintenance/branch-cleanup-20261103.json').read_text())
    if plan['repository'] != repository:
        raise ValueError('Cleanup plan identifies another repository')
    output = Path('branch-cleanup-result.json')
    try:
        report = perform(plan, open_pull_heads(repository))
    except Exception as error:
        output.write_text(json.dumps({'status': 'failed', 'error': str(error)}, indent=2) + '\n')
        raise
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
