#!/bin/sh
# A one-time resumable download and local smoke check. No login item or scheduled task.
set -eu
cd "$(dirname "$0")"
mkdir -p tools/local-ai build/local-ai-setup
if [ -f tools/local-ai/setup.pid ] && kill -0 "$(cat tools/local-ai/setup.pid)" 2>/dev/null; then
  printf 'Local AI setup is already running. Progress: %s/tools/local-ai/setup.log\n' "$PWD"
  exit 0
fi
./build.sh
javac --release 17 -encoding UTF-8 -cp build/classes -d build/local-ai-setup \
  src/test/java/org/stillword/LocalAiInstallCheck.java
python3 - <<'PY'
from pathlib import Path
import subprocess
with open('tools/local-ai/setup.log', 'ab', buffering=0) as log:
    process = subprocess.Popen(
        ['java', '-cp', 'build/classes:build/local-ai-setup', 'org.stillword.LocalAiInstallCheck'],
        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True, close_fds=True)
Path('tools/local-ai/setup.pid').write_text(str(process.pid) + '\n')
PY
printf 'Local AI setup started. Progress: %s/tools/local-ai/setup.log\n' "$PWD"
printf 'Result: %s/tools/local-ai/setup-status.txt\n' "$PWD"
