#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
exec java -cp build/classes org.stillword.LocalAiService "$@"
