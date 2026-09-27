#!/usr/bin/env python3
"""Download the pinned public Android/JDK toolchain into a caller-selected directory."""
import concurrent.futures, hashlib, os, pathlib, shutil, stat, sys, tarfile, time, urllib.request, zipfile

root = pathlib.Path(sys.argv[1]).resolve()
root.mkdir(parents=True, exist_ok=True)
packages = [
    ('ndk', 'https://dl.google.com/android/repository/android-ndk-r28c-linux.zip', root / 'android-ndk-r28c'),
    ('platform', 'https://dl.google.com/android/repository/platform-37.0_r02.zip', root / 'sdk/platforms/android-37.0'),
    ('build-tools', 'https://dl.google.com/android/repository/build-tools_r36_linux.zip', root / 'sdk/build-tools/36.0.0'),
    ('cmake', 'https://dl.google.com/android/repository/cmake-3.22.1-linux.zip', root / 'sdk/cmake/3.22.1'),
    ('jdk', 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz', root / 'jdk21'),
]

def provision(item):
    name, url, dest = item
    if (dest / '.complete').exists():
        print(name, 'already extracted', flush=True)
        return
    archive = root / (name + ('.tar.gz' if name == 'jdk' else '.zip'))
    if not archive.exists():
        temporary = archive.with_suffix('.partial')
        for attempt in range(3):
            try:
                with urllib.request.urlopen(url, timeout=180) as response, temporary.open('wb') as out:
                    shutil.copyfileobj(response, out, 1024 * 1024)
                temporary.replace(archive)
                break
            except Exception:
                if attempt == 2: raise
        print(name, 'downloaded', archive.stat().st_size, flush=True)
    stage = root / ('extract-' + name)
    stage.mkdir(exist_ok=True)
    if name == 'jdk':
        with tarfile.open(archive) as t: t.extractall(stage, filter='data')
    else:
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                path = stage / info.filename
                if '..' in pathlib.PurePosixPath(info.filename).parts or info.filename.startswith('/'):
                    raise ValueError('Unsafe archive path')
                if info.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                    continue
                path.parent.mkdir(parents=True, exist_ok=True)
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode):
                    if path.is_symlink(): path.unlink()
                    path.symlink_to(z.read(info).decode())
                else:
                    with z.open(info) as src, path.open('wb') as dst: shutil.copyfileobj(src, dst)
                    if mode & 0o777: path.chmod(mode & 0o777)
    children = list(stage.iterdir())
    content = children[0] if len(children) == 1 and children[0].is_dir() else stage
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists(): shutil.rmtree(dest)
    content.rename(dest)
    (dest / '.complete').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '\n')
    print(name, 'ready', flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(provision, packages))
ndk_link = root / 'sdk/ndk/28.2.13676358'
ndk_link.parent.mkdir(parents=True, exist_ok=True)
if not ndk_link.exists(): ndk_link.symlink_to(root / 'android-ndk-r28c', target_is_directory=True)
license_dir = root / 'sdk/licenses'
license_dir.mkdir(exist_ok=True)
(license_dir / 'android-sdk-license').write_text('24333f8a63b6825ea9c5514f83c2829b004d1fee\nd56f5187479451eabf01fb78af6dfcb131a6481e\n8933bad161af4178b1185d1a37fbf41ea5269c55\n')
print('Android/JDK toolchain ready', flush=True)
