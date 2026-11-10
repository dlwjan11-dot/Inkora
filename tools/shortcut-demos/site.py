#!/usr/bin/env python3
"""Puts the shortcut cards into index.html (between the demos:start / demos:end marks) and
writes the app's ShortcutData.kt. Run from the repo root after changing make.py."""
import subprocess, re
run = lambda what: subprocess.run(['python3', 'tools/shortcut-demos/make.py', what], capture_output=True, text=True, check=True).stdout
open('app/src/main/java/com/abdulmedia/inkora/ShortcutData.kt', 'w').write(run('kt'))
t = open('index.html').read()
m = re.search(r'var DATA = .*?;\n  var host = document.getElementById\(\'demos\'\)', t, flags=re.S)
t = t[:m.start()] + 'var DATA = ' + run('web').strip() + ";\n  var host = document.getElementById('demos')" + t[m.end():]
open('index.html', 'w').write(t)
print('ok')
