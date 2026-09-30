#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
rm -rf build/test-classes
mkdir -p build/test-classes
find src/test/java -name '*.java' > build/test-sources.txt
javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
exec java --add-modules jdk.httpserver -Djava.awt.headless=true -ea -cp build/classes:build/test-classes org.stillword.RegressionSuite
