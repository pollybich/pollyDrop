"""Run browser regressions and attach sanitized failure details to CI annotations."""
import os
from pathlib import Path
import re
import subprocess
import sys

for name in ('smoke.py', 'languages.py', 'resume.py'):
    result = subprocess.run([sys.executable, str(Path(__file__).with_name(name))], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = re.sub(r'(?<=[/])[A-Za-z0-9_-]{43}(?=[/\s?\'"#]|$)', '[redacted]', result.stdout)
    print(output, end='', flush=True)
    if result.returncode:
        if os.environ.get('GITHUB_ACTIONS'):
            detail = output[-6000:].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print(f'::error title={name} failed::{detail}', flush=True)
        raise SystemExit(result.returncode)
