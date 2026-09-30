#!/bin/sh
set -eu
cd "$(dirname "$0")"
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
jar --create --file build/StillWord.jar --main-class org.stillword.StillWord -C build/classes .
printf 'Built: %s/build/StillWord.jar\n' "$PWD"
