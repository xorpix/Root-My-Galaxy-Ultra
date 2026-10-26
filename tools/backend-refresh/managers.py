#!/usr/bin/env python3
"""Refresh README manager links from verified official upstream build artifacts."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from common import REPOSITORIES, load_config, write_json

MANAGERS = {
    'kernelsu': ('KernelSU', 'build-manager.yml', 'manager', 'me_weishu_kernelsu'),
    'kernelsu-next': ('KernelSU-Next', 'build-manager-ci.yml', 'manager', 'com_rifsxd_ksunext'),
    'resukisu': ('BakaSU', 'build-manager.yml', 'Manager-release', 'org_bakasu_bakasu'),
    # KowSU changes the application id but keeps KernelSU's Kotlin/JNI namespace.
    'kowsu': ('KowSU', 'build-manager.yml', 'manager', 'me_weishu_kernelsu'),
}
MAX_ARCHIVE = 128 * 1024 * 1024
MAX_APK = 96 * 1024 * 1024


class GitHub:
    def json(self, path):
        url = 'https://api.github.com/' + path
        headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'RMGU-manager-refresh'}
        token = os.environ.get('GH_TOKEN')
        if token:
            headers['Authorization'] = 'Bearer ' + token
        try:
            return json.loads(self.read(url, 4 * 1024 * 1024, headers))
        except urllib.error.HTTPError as error:
            # Installation tokens may be scoped to RMGU, while these repositories are public.
            if not token or error.code not in (401, 403, 404):
                raise
            headers.pop('Authorization')
            return json.loads(self.read(url, 4 * 1024 * 1024, headers))

    @staticmethod
    def read(url, limit, headers=None):
        request = urllib.request.Request(url, headers=headers or {'User-Agent': 'RMGU-manager-refresh'})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError('Manager response exceeds its size limit')
        return data

    def archive(self, url):
        # Constructed nightly.link URLs only; the API token is never sent to download hosts.
        return self.read(url, MAX_ARCHIVE)


def part(data, offset, size):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ValueError('Truncated manager ELF')
    return data[offset:offset + size]


def manager_uapi(data, package):
    """Read the ARM64 JNI UAPI getter without executing downloaded native code."""
    if (len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01'
            or struct.unpack_from('<HH', data, 16) != (3, 183)):
        raise ValueError('Manager library is not an ARM64 shared ELF')
    phoff, shoff = struct.unpack_from('<QQ', data, 32)
    phsize, phnum, shsize, shnum = struct.unpack_from('<HHHH', data, 54)
    if phsize != 56 or shsize != 64 or not 0 < phnum <= 256 or not 0 < shnum <= 4096:
        raise ValueError('Unsupported manager ELF tables')
    sections = [struct.unpack('<IIQQQQIIQQ', part(data, shoff + i * shsize, shsize))
                for i in range(shnum)]
    wanted = ('Java_' + package + '_Natives_getManagerUAPIVersion').encode()
    functions = []
    for section in sections:
        if section[1] != 11:  # SHT_DYNSYM
            continue
        if section[9] != 24 or section[5] % 24 or section[6] >= shnum:
            raise ValueError('Invalid manager symbol table')
        strings = sections[section[6]]
        names = part(data, strings[4], strings[5])
        table = part(data, section[4], section[5])
        for offset in range(0, len(table), 24):
            name, info, _, index, address, size = struct.unpack_from('<IBBHQQ', table, offset)
            end = names.find(b'\0', name)
            if end < name:
                raise ValueError('Invalid manager symbol name')
            if names[name:end] == wanted and index and info & 15 == 2:
                functions.append((address, size))
    if len(functions) != 1:
        raise ValueError('Expected one manager UAPI JNI getter')
    address, size = functions[0]
    if size not in (8, 12, 16):
        raise ValueError('Unrecognized manager UAPI getter; review the new build format')
    code = None
    for i in range(phnum):
        header = struct.unpack('<IIQQQQQQ', part(data, phoff + i * phsize, phsize))
        if header[0] == 1 and header[3] <= address and address + size <= header[3] + header[5]:
            code = part(data, header[2] + address - header[3], size)
            break
    if code is None:
        raise ValueError('Manager UAPI getter is outside its load segment')
    words = list(struct.unpack('<' + 'I' * (size // 4), code))
    while words and words[0] in (0xd503245f, 0xd503201f):  # BTI c / NOP
        words.pop(0)
    if len(words) != 2 or words[0] & 0xffe0001f != 0x52800000 or words[1] != 0xd65f03c0:
        raise ValueError('Unrecognized manager UAPI getter; review the new build format')
    return (words[0] >> 5) & 0xffff


def verify_archive(data, name, uapi, expected_version=None):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate manager artifact entries')
        suffix = '-arm64-v8a-release.apk' if name == 'resukisu' else '-release.apk'
        apks = [entry for entry in names if entry.endswith(suffix)]
        if len(apks) != 1 or archive.getinfo(apks[0]).file_size > MAX_APK:
            raise ValueError('Expected one release manager APK for ARM64')
        filename = Path(apks[0]).name
        version = re.search(r'_(\d+)(?:-arm64-v8a)?-release\.apk$', filename)
        if not version or (expected_version is not None and int(version[1]) != expected_version):
            raise ValueError('Manager build number does not match the selected upstream revision')
        apk = archive.read(apks[0])
    with zipfile.ZipFile(io.BytesIO(apk)) as archive:
        names = archive.namelist()
        library = 'lib/arm64-v8a/libkernelsu.so'
        if len(names) != len(set(names)) or archive.getinfo(library).file_size > 16 * 1024 * 1024:
            raise ValueError('Invalid manager APK library entries')
        reported = manager_uapi(archive.read(library), MANAGERS[name][3])
    if reported != uapi:
        raise ValueError(f'Manager reports UAPI {reported}; this bundle requires {uapi}')
    return {'apk': filename, 'apk_sha256': hashlib.sha256(apk).hexdigest(),
            'manager_version': int(version[1]), 'uapi': reported}


def validate_run(run, target, commit=None):
    repo, branch = target['repository'], target['branch']
    return (run.get('status') == 'completed' and run.get('conclusion') == 'success'
            and run.get('event') in ('push', 'workflow_dispatch')
            and run.get('head_branch') == branch
            and (run.get('repository') or {}).get('full_name') == repo
            and (run.get('head_repository') or {}).get('full_name') == repo
            and re.fullmatch(r'[0-9a-f]{40}', run.get('head_sha') or '') is not None
            and (commit is None or run.get('head_sha') == commit))


def download(client, name, target, run, uapi, now, exact, retained=False):
    repo = target['repository']
    workflow, artifact_name = MANAGERS[name][1:3]
    if not validate_run(run, target, target['commit'] if exact else None):
        raise ValueError('Manager run is not a successful official source-branch build')
    if run.get('path') != '.github/workflows/' + workflow:
        raise ValueError('Unexpected manager workflow')
    artifacts = client.json(f'repos/{repo}/actions/runs/{run["id"]}/artifacts?per_page=100')['artifacts']
    matches = [a for a in artifacts if a['name'] == artifact_name and not a['expired']]
    if len(matches) != 1:
        raise ValueError('Release manager artifact is missing or expired')
    artifact = matches[0]
    expires = datetime.fromisoformat(artifact['expires_at'].replace('Z', '+00:00'))
    if expires.tzinfo is None or expires <= now or not 0 < artifact['size_in_bytes'] <= MAX_ARCHIVE:
        raise ValueError('Manager artifact is expired or exceeds its size limit')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', artifact.get('digest', '')):
        raise ValueError('Manager artifact has no official SHA-256 digest')
    url = f'https://nightly.link/{repo}/actions/runs/{run["id"]}/{artifact_name}.zip'
    data = client.archive(url)
    if len(data) != artifact['size_in_bytes'] or 'sha256:' + hashlib.sha256(data).hexdigest() != artifact['digest']:
        raise ValueError('Manager download differs from the official artifact digest')
    verified = verify_archive(data, name, uapi, target['version'] if exact else None)
    return dict(verified, status='matched' if exact else ('retained' if retained else 'compatible'), repository=repo,
                source_commit=run['head_sha'], run_id=run['id'], artifact_id=artifact['id'],
                artifact_sha256=artifact['digest'][7:], expires_at=artifact['expires_at'],
                download_url=url, build_url=f'https://github.com/{repo}/actions/runs/{run["id"]}')


def resolve(client, name, target, old_cell, uapi, now):
    repo, branch, _ = REPOSITORIES[name]
    if (target['repository'], target['branch']) != (repo, branch):
        raise ValueError('Unreviewed manager repository or branch')
    errors = []
    query = urllib.parse.urlencode({'head_sha': target['commit'], 'branch': branch,
                                   'status': 'success', 'exclude_pull_requests': 'true', 'per_page': 100})
    try:
        runs = client.json(f'repos/{repo}/actions/workflows/{MANAGERS[name][1]}/runs?{query}')['workflow_runs']
        candidates = [r for r in runs if validate_run(r, target, target['commit'])]
        for run in sorted(candidates, key=lambda r: r['id'], reverse=True)[:3]:
            try:
                return download(client, name, target, run, uapi, now, True), []
            except (ValueError, KeyError, OSError, zipfile.BadZipFile, struct.error) as error:
                errors.append(str(error))
        errors.append('No usable manager build for the selected source revision')
    except (ValueError, KeyError, OSError) as error:
        errors.append(str(error))
    # Upstreams can delete old CI runs or skip the manager workflow for a source-only edit.
    # A newer official manager with the same UAPI remains usable with the bundled driver.
    query = urllib.parse.urlencode({'branch': branch, 'status': 'success',
                                   'exclude_pull_requests': 'true', 'per_page': 20})
    try:
        runs = client.json(f'repos/{repo}/actions/workflows/{MANAGERS[name][1]}/runs?{query}')['workflow_runs']
        candidates = [r for r in runs if validate_run(r, target)]
        for run in sorted(candidates, key=lambda r: r['id'], reverse=True)[:3]:
            try:
                result = download(client, name, target, run, uapi, now, run['head_sha'] == target['commit'])
                return result, errors + ['Using the latest verified official UAPI-compatible manager']
            except (ValueError, KeyError, OSError, zipfile.BadZipFile, struct.error) as error:
                errors.append(str(error))
    except (ValueError, KeyError, OSError) as error:
        errors.append(str(error))
    old = re.search(r'https://github\.com/' + re.escape(repo) + r'/actions/runs/(\d+)', old_cell)
    if old:
        try:
            run = client.json(f'repos/{repo}/actions/runs/{old[1]}')
            result = download(client, name, target, run, uapi, now, False, retained=True)
            return result, errors + ['Keeping the previous verified UAPI manager link']
        except urllib.error.HTTPError as error:
            errors.append(str(error))
            if error.code != 404:
                return {'status': 'unchanged', 'expected_commit': target['commit']}, errors + ['Previous README link retained; check the upstream build page']
        except OSError as error:
            errors.append(str(error))
            # A transient network failure does not establish that the previous link is broken.
            return {'status': 'unchanged', 'expected_commit': target['commit']}, errors + ['Previous README link retained; check the upstream build page']
        except (ValueError, KeyError, zipfile.BadZipFile, struct.error) as error:
            errors.append(str(error))
    # Remove a known expired/invalid download rather than advertising it as verified.
    return {'status': 'unavailable', 'expected_commit': target['commit']}, errors + ['No verified download available; use the official build page']


def update(project, receipt, client=None, now=None):
    config = load_config(project / 'tools/backend-refresh/targets.json')
    client = client or GitHub()
    now = now or datetime.now(timezone.utc)
    path = project / 'README.md'
    original = text = path.read_text()
    records, warnings = {}, []
    for name, target in config['backends'].items():
        label = MANAGERS[name][0]
        pattern = re.compile(r'^\| ' + re.escape(label) + r' \| [^\n]+? \| ([^\n]+) \|$', re.M)
        matches = list(pattern.finditer(text))
        if len(matches) != 1:
            raise ValueError(f'Expected exactly one README manager row for {label}')
        match = matches[0]
        result, problems = resolve(client, name, target, match[1], target['uapi'], now)
        records[name] = result
        warnings.extend(f'{label}: {message}' for message in problems)
        if result['status'] in ('matched', 'compatible', 'retained'):
            caption = 'Manager APKs (ZIP)' if name == 'resukisu' else 'Manager APK (ZIP)'
            cell = f'[{caption}]({result["download_url"]}) · [Official build]({result["build_url"]})'
            row = f'| {label} | `{target["version"]} / {target["uapi"]}` | {cell} |'
            text = text[:match.start()] + row + text[match.end():]
        elif result['status'] == 'unavailable':
            builds = f'https://github.com/{target["repository"]}/actions/workflows/{MANAGERS[name][1]}'
            cell = f'Verified download unavailable · [Official builds]({builds})'
            row = f'| {label} | `{target["version"]} / {target["uapi"]}` | {cell} |'
            text = text[:match.start()] + row + text[match.end():]
    start = text.index('Download the ZIP, extract it, and install the APK.')
    end = text.index('These are pinned upstream development revisions', start)
    guidance = ('Download the ZIP, extract it, and install the APK. For BakaSU on a Samsung phone,\n'
                'choose the APK ending in `-arm64-v8a-release.apk` from the archive.\n'
                'These official upstream development builds are verified against each backend\'s UAPI.\n'
                'A manager build number can differ from the bundled driver version.\n'
                'The weekly backend build prefers managers from its selected source revisions;\n'
                'otherwise it verifies a current UAPI-compatible official manager or retains\n'
                'the previous verified link, with a warning in the workflow summary.\n'
                'The download links use [nightly.link](https://nightly.link/)\n'
                'to retrieve the original GitHub artifacts without a GitHub login; **Official build**\n'
                'opens their upstream source and artifact records. GitHub artifacts can expire.\n\n')
    text = text[:start] + guidance + text[end:]
    report = {'schema': 2, 'checked_utc': now.isoformat(),
              'required_uapi': {n: t['uapi'] for n, t in config['backends'].items()},
              'readme_changed': text != original, 'managers': records, 'warnings': warnings}
    write_json(receipt, report)
    if text != original:
        path.write_text(text)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    args = parser.parse_args()
    report = update(args.project.resolve(), args.receipt.resolve())
    summary = ['### Manager download verification', '']
    for name, result in report['managers'].items():
        summary.append(f'- {MANAGERS[name][0]}: {result["status"]}' +
                       (f', manager {result["manager_version"]} / UAPI {result["uapi"]}'
                        if 'manager_version' in result else ''))
    for warning in report['warnings']:
        escaped = warning.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::warning::' + escaped)
        summary.append('- Warning: ' + warning.replace('\n', ' '))
    print('\n'.join(summary))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as output:
            output.write('\n'.join(summary) + '\n')


if __name__ == '__main__':
    main()
