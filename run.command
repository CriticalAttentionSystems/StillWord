#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
exec java -Dapple.awt.application.name=Stillword -jar build/StillWord.jar "$@"
