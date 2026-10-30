#!/usr/bin/env python3
"""Catch a small, high-confidence subset of hard-coded phone UI text.

This supplements Android lint and is not a complete Kotlin localization audit.
"""
import re
import sys
from pathlib import Path

CALL = re.compile(r'(?<![\w.])Text\s*\(\s*(?:text\s*=\s*)?"([^"\n\\]*)"|\.setContentTitle\s*\(\s*"([^"\n\\]*)"')
BRANDS = {'OSM', 'Refuges.info'}
INTERPOLATION = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z_0-9]*')

def findings(source):
    for match in CALL.finditer(source):
        value = next(group for group in match.groups() if group is not None)
        visible = INTERPOLATION.sub('', value)
        # A remaining unit symbol such as the m in a distance is not prose.
        visible = re.sub(r'(?<![A-Za-z])m(?![A-Za-z])', '', visible)
        if value not in BRANDS and re.search(r'[A-Za-z]', visible):
            yield source.count('\n', 0, match.start()) + 1, value

def main():
    root = Path(__file__).resolve().parent.parent
    sources = root / 'glancemapcompanionapp/src/main/java'
    count = 0
    for path in sorted(sources.rglob('*.kt')):
        for line, value in findings(path.read_text()):
            print(f'{path.relative_to(root)}:{line}: hard-coded UI text: {value}')
            count += 1
    if count:
        print(f'{count} possible hard-coded UI string(s); use a string resource.')
        return 1
    print('No direct hard-coded phone Text or notification title literals found.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
