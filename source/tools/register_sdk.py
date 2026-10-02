#!/usr/bin/env python3
"""Register extracted SDK archives using Google's authoritative package metadata."""
from pathlib import Path
import copy, io, sys, urllib.request
import xml.etree.ElementTree as ET

toolchain = Path(sys.argv[1]).resolve()
metadata = toolchain / 'android-repository.xml'
if not metadata.exists():
    with urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=120) as r:
        metadata.write_bytes(r.read())
data = metadata.read_bytes()
namespaces = dict(n for _, n in ET.iterparse(io.BytesIO(data), events=['start-ns']))
ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
repo = ET.fromstring(data)
packages = {'platforms;android-37.0', 'build-tools;36.0.0', 'ndk;28.2.13676358', 'cmake;3.22.1'}
found = set()
for remote in repo.findall('remotePackage'):
    name = remote.attrib['path']
    if name not in packages: continue
    local = ET.Element('common:repository', {
        'xmlns:common': 'http://schemas.android.com/repository/android/common/02',
        'xmlns:sdk': namespaces['sdk'], 'xmlns:generic': namespaces['generic']})
    for license in repo.findall('license'): local.append(copy.deepcopy(license))
    package = ET.SubElement(local, 'localPackage', path=name, obsolete='false')
    for item in remote:
        if item.tag in {'type-details','revision','display-name','uses-license','dependencies'}:
            package.append(copy.deepcopy(item))
    path = toolchain/'sdk'/name.replace(';','/')/'package.xml'
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(local).write(path, encoding='utf-8', xml_declaration=True)
    found.add(name)
assert found == packages, packages-found
print('Registered the four extracted SDK packages.')
