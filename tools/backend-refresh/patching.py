"""Apply reviewed patches directly, or use Git history when the context has drifted."""
import os
from pathlib import Path
import subprocess
import tempfile


def _git(repo, args, env=None):
    return subprocess.run(['git', *args], cwd=repo, env=env, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def _clean(repo):
    status = _git(repo, ['status', '--porcelain', '--untracked-files=all'])
    if status.returncode or status.stdout.strip():
        raise ValueError('Compatibility patches require a clean upstream checkout')


def _record(log, title, result):
    with Path(log).open('a') as output:
        output.write(f'\n{title} (exit {result.returncode})\n{result.stdout}')


def check(repo, patch, log):
    """Dry-run both methods without changing the checkout or its real index."""
    _clean(repo)
    patch = str(Path(patch).resolve())
    Path(log).write_text(f'Checking {Path(patch).name}\n')
    direct = _git(repo, ['apply', '--check', patch])
    _record(log, 'Direct application', direct)
    if direct.returncode == 0:
        return 'direct'

    # --3way implies --index. Use a private index even for the dry run, so a conflict
    # cannot leave partially staged source behind for a later build to consume.
    with tempfile.TemporaryDirectory(prefix='rmgu-patch-index-') as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / 'index'))
        index = _git(repo, ['read-tree', 'HEAD'], env)
        _record(log, 'Prepare private index', index)
        if index.returncode:
            raise RuntimeError(f'Could not prepare compatibility check; see {log}')
        # Actually merge into the disposable index: --check alone can report success
        # without resolving the hunks, so it does not reliably detect conflicts.
        merged = _git(repo, ['apply', '--cached', '--3way', patch], env)
        _record(log, 'Three-way application', merged)
        conflicts = _git(repo, ['ls-files', '--unmerged'], env)
        if merged.returncode == 0 and conflicts.returncode == 0 and not conflicts.stdout:
            _clean(repo)
            return 'three-way'

    _clean(repo)
    raise RuntimeError(f'Compatibility patch needs review: {Path(patch).name}; see {log}')


def apply(repo, patch, log):
    method = check(repo, patch, log)
    args = ['apply'] + (['--3way'] if method == 'three-way' else [])
    applied = _git(repo, [*args, str(Path(patch).resolve())])
    _record(log, f'Apply ({method})', applied)
    conflicts = _git(repo, ['ls-files', '--unmerged'])
    if applied.returncode or conflicts.returncode or conflicts.stdout:
        raise RuntimeError(f'Compatibility application failed; see {log}')
    return method
