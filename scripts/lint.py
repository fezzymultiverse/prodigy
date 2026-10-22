#!/usr/bin/env python3
# S.O.I · Bojack Security: build lint for Prodigy Syncing. Exit 1 on any failure.
import json, os, re, subprocess, sys, tempfile
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
rd = lambda p: open(os.path.join(ROOT, p), encoding='utf-8').read()
html, sw, pkg, readme = rd('www/index.html'), rd('www/sw.js'), json.loads(rd('package.json')), rd('README.md')
errs = []

# 1. versions must agree everywhere
m = re.search(r"const APPV='V(\d+)'", html)
if not m:
    sys.exit('LINT FAIL: APPV not found')
v = m.group(1)
boot = re.search(r'<div id="boot">.*?</div>', html, re.S)
bv = re.findall(r'>V(\d+)</text>', boot.group(0)) if boot else []
if bv != [v]: errs.append('boot screen version %s != APPV V%s' % (bv, v))
if "'prodigy-v%s'" % v not in sw: errs.append('sw.js cache name is not prodigy-v%s' % v)
if pkg.get('version') != v + '.0.0': errs.append('package.json version %s != %s.0.0' % (pkg.get('version'), v))
if not readme.startswith('# Prodigy Syncing V' + v): errs.append('README title is not V' + v)
if ('V%s:' % v) not in readme: errs.append('README has no V%s entry in the version history' % v)

# 2. JS syntax, every inline script
scripts = re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', html, re.S)
for i, s in enumerate(scripts):
    if not s.strip(): continue
    with tempfile.NamedTemporaryFile('w', suffix='.js', delete=False, encoding='utf-8') as f:
        f.write(s); name = f.name
    r = subprocess.run(['node', '--check', name], capture_output=True, text=True)
    os.unlink(name)
    if r.returncode: errs.append('script #%d syntax error: %s' % (i, r.stderr.strip().splitlines()[0:3]))

# 3. wired element ids must exist (in the HTML, or be built by JS: id="..." strings, .id=, sel('..'))
have = set(re.findall(r'\bid=\\?["\']([A-Za-z0-9_\-]+)', html))
have |= set(re.findall(r"\.id\s*=\s*['\"]([A-Za-z0-9_\-]+)['\"]", html))
have |= set(re.findall(r"\bsel\('([A-Za-z0-9_\-]+)'", html))
used = set(re.findall(r"\$\('([A-Za-z0-9_\-]+)'\)\.(?:onclick|oninput|onchange|onkeydown|onsubmit|addEventListener)", html))
miss = sorted(used - have)
if miss: errs.append('JS wires ids that do not exist: ' + ', '.join(miss))

if errs:
    print('LINT FAIL (V%s):' % v)
    for e in errs: print(' -', e)
    sys.exit(1)
print('LINT OK · V%s · %d scripts · %d wired ids checked' % (v, len(scripts), len(used)))
