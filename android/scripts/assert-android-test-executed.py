#!/usr/bin/env python3
"""Fail CI if an explicitly selected instrumentation class did not run."""
import glob
import sys
import xml.etree.ElementTree as ET

if len(sys.argv) != 2:
    raise SystemExit('Usage: assert-android-test-executed.py <fully-qualified-class>')
expected = sys.argv[1]
seen = []
for path in glob.glob('app/build/outputs/androidTest-results/**/*.xml', recursive=True):
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        if case.get('classname') == expected:
            seen.append(case)
if not seen or not any(case.find('skipped') is None for case in seen):
    raise SystemExit(f'Expected instrumentation test class was not executed: {expected}')
if any(case.find('failure') is not None or case.find('error') is not None for case in seen):
    raise SystemExit(f'Instrumentation test class failed: {expected}')
print(f'Confirmed instrumentation execution: {expected} ({len(seen)} test(s))')
