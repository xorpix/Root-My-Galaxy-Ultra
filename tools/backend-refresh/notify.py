#!/usr/bin/env python3
"""Maintain one bot-owned compatibility alert, without daily duplicate issues."""
import argparse
import json
import os
import re
import urllib.request


START = '<!-- rmgu-backend-compatibility:start -->'
END = '<!-- rmgu-backend-compatibility:end -->'
TITLE = 'Backend compatibility check needs attention'


def request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request('https://api.github.com/' + path, data=data, method=method,
                                 headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                                          'Accept': 'application/vnd.github+json',
                                          'Content-Type': 'application/json',
                                          'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def section(repository, run, result):
    url = f'https://github.com/{repository}/actions/runs/{run}'
    if result == 'failure':
        text = ('The daily backend compatibility check failed. The next weekly release may '
                'need a Samsung patch or build-kit update.\n\n'
                f'Inspect [the failed run]({url}) and its compatibility artifact for the '
                'exact upstream commits and patch-check logs. Release validation still '
                'blocks incompatible builds from being published.')
    else:
        text = f'The backend compatibility check passed again: [successful run]({url}).'
    return f'{START}\n{text}\n{END}'


def update(repository, run, result, api=request):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Expected an owner/repository name')
    if not re.fullmatch(r'[0-9]+', str(run)) or result not in ('success', 'failure'):
        raise ValueError('Expected a run id and a completed success/failure result')
    root = f'repos/{repository}/issues'
    alerts = []
    page = 1
    while True:
        issues = api('GET', f'{root}?state=open&per_page=100&page={page}')
        alerts.extend(issue for issue in issues
                      if 'pull_request' not in issue
                      and issue.get('user', {}).get('login') == 'github-actions[bot]'
                      and START in (issue.get('body') or '')
                      and END in (issue.get('body') or ''))
        if len(issues) < 100:
            break
        page += 1
    if len(alerts) > 1:
        raise ValueError('Multiple bot-owned compatibility alerts require review')
    generated = section(repository, run, result)
    if not alerts:
        if result == 'failure':
            issue = api('POST', root, {'title': TITLE, 'body': generated})
            print(f'Opened compatibility alert #{issue["number"]}')
        else:
            print('Compatibility passed; no open alert to resolve')
        return
    issue = alerts[0]
    body = issue['body']
    # Preserve any notes a maintainer adds outside the generated section.
    begin = body.index(START)
    end = body.index(END, begin) + len(END)
    body = body[:begin] + generated + body[end:]
    payload = {'body': body}
    if result == 'success':
        payload.update(state='closed', state_reason='completed')
    api('PATCH', f'{root}/{issue["number"]}', payload)
    print(f'{"Resolved" if result == "success" else "Updated"} compatibility alert #{issue["number"]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--result', required=True, choices=['success', 'failure'])
    args = parser.parse_args()
    update(args.repository, args.run, args.result)


if __name__ == '__main__':
    main()
