#!/usr/bin/env python3
"""Check the complete Android catalog, format arguments, plurals and locale registration."""
import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])')
UNRESOLVED = re.compile(r'ZXQ\d+ZXQ|[\u2047\ufffd]')
QUANTITIES = {
    'de': {'one', 'other'}, 'fr': {'one', 'many', 'other'},
    'ja': {'other'}, 'ko': {'other'}, 'pt-BR': {'one', 'many', 'other'},
    'ru': {'one', 'few', 'many', 'other'}, 'tr': {'one', 'other'},
    'uz': {'one', 'other'}, 'vi': {'other'},
    'zh-CN': {'other'}, 'zh-TW': {'other'},
}


def arguments(text):
    """Map Java Formatter argument indices to their conversion types; allow reordering."""
    result = {}
    next_index = 1
    for match in FORMAT.finditer(text):
        index, conversion = match.groups()
        if conversion in ('%', 'n'):
            continue
        position = int(index) if index else next_index
        if not index:
            next_index += 1
        if position in result and result[position] != conversion:
            raise ValueError(f'argument {position} has conflicting conversion types')
        result[position] = conversion
    return result


def catalog(directory):
    result = {}
    errors = []
    for path in sorted(directory.glob('*.xml')):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            errors.append(f'{path}: {error}')
            continue
        for element in root:
            if element.tag not in ('string', 'plurals', 'string-array'):
                continue
            name = element.get('name')
            if not name:
                errors.append(f'{path}: resource has no name')
                continue
            if name in result:
                errors.append(f'{path}: duplicate resource {name}')
            if element.tag == 'plurals':
                quantities = [item.get('quantity') for item in element]
                if len(quantities) != len(set(quantities)):
                    errors.append(f'{path}: duplicate plural quantity in {name}')
            result[name] = element
    return result, errors


def text(element):
    return ''.join(element.itertext())


def locale_tag(folder):
    return folder.removeprefix('values-').replace('-r', '-')


def check(project):
    res = project / 'app/src/main/res'
    base, errors = catalog(res / 'values')
    required = {name: element for name, element in base.items()
                if element.get('translatable') != 'false'}
    locales = {}
    for folder in sorted(res.glob('values-*')):
        if not (folder / 'strings.xml').is_file():
            continue
        tag = locale_tag(folder.name)
        locales[tag] = folder
        entries, problems = catalog(folder)
        errors.extend(problems)
        for name in sorted(required.keys() - entries.keys()):
            errors.append(f'{tag}: missing {name}')
        for name in sorted(entries.keys() - base.keys()):
            errors.append(f'{tag}: unknown resource {name}')
        for name in sorted(required.keys() & entries.keys()):
            source, target = required[name], entries[name]
            prefix = f'{tag}: {name}'
            if source.tag != target.tag:
                errors.append(f'{prefix}: expected {source.tag}, found {target.tag}')
                continue
            if not text(target).strip():
                errors.append(f'{prefix}: empty translation')
            if source.tag == 'plurals':
                original = {item.get('quantity'): item for item in source}
                translated = {item.get('quantity'): item for item in target}
                needed = QUANTITIES.get(tag, {'other'})
                for quantity in sorted(translated.keys() - {'zero', 'one', 'two', 'few', 'many', 'other'}):
                    errors.append(f'{prefix}: invalid plural quantity {quantity}')
                for quantity in sorted(needed - translated.keys()):
                    errors.append(f'{prefix}: missing plural quantity {quantity}')
                # A locale can need count arguments in forms where English omits
                # them (e.g. Russian "one" also covers 21). The call supplies the
                # same argument list for every quantity, so use the union here.
                available = {}
                for item in original.values():
                    available.update(arguments(text(item)))
                pairs = [(f'{prefix}.{quantity}', original.get(quantity, original['other']), item)
                         for quantity, item in translated.items()]
            elif source.tag == 'string-array':
                if len(source) != len(target):
                    errors.append(f'{prefix}: array length differs from English')
                pairs = [(f'{prefix}[{i}]', a, b) for i, (a, b) in enumerate(zip(source, target))]
                available = None
            else:
                pairs = [(prefix, source, target)]
                available = None
            for label, original, translated in pairs:
                if UNRESOLVED.search(text(translated)):
                    errors.append(f'{label}: unresolved translation marker or character')
                try:
                    expected, actual = arguments(text(original)), arguments(text(translated))
                except ValueError as error:
                    errors.append(f'{label}: {error}')
                    continue
                if available is None:
                    if expected != actual:
                        errors.append(f'{label}: format arguments {actual} differ from {expected}')
                elif (any(available.get(i) != conversion for i, conversion in actual.items())
                      or any(actual.get(i) != conversion for i, conversion in expected.items())):
                    errors.append(f'{label}: plural format arguments {actual} conflict with {available}')
                if not text(translated).strip():
                    errors.append(f'{label}: empty translation')
    android = '{http://schemas.android.com/apk/res/android}'
    registered = {element.get(android + 'name') for element in ET.parse(res / 'xml/locales_config.xml').getroot()}
    expected_locales = {'en', *locales}
    if registered != expected_locales:
        errors.append(f'locale configuration: expected {sorted(expected_locales)}, found {sorted(registered)}')
    activity = (project / 'app/src/main/java/dev/busung/s25uroot/MainActivity.kt').read_text()
    offered = set(re.findall(r'LanguageOption\(\s*R\.string\.\w+,\s*"([^"]*)"\s*\)', activity)) - {''}
    if offered != expected_locales:
        errors.append(f'language selector: expected {sorted(expected_locales)}, found {sorted(offered)}')
    return errors, len(required), len(locales)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, default=PROJECT)
    args = parser.parse_args()
    errors, count, locales = check(args.project)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        print(f'{len(errors)} translation errors', file=sys.stderr)
        return 1
    print(f'All {count} resources are translated in {locales} locales; formats, plurals and language registration pass.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
