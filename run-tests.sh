#!/bin/sh
# Compile the simulator and run the test suite.
#
# Needs two jars in lib/ (downloaded on first run):
#   mongo-java-driver  - the legacy com.mongodb.* API this project is written against
#   junit-platform-console-standalone - test runner
set -e
cd "$(dirname "$0")"

MONGO_JAR=lib/mongo-java-driver-3.12.14.jar
JUNIT_JAR=lib/junit-platform-console-standalone-1.10.2.jar
# Compiled output is wiped first: javac only rebuilds the sources present, so class
# files left over from another branch would otherwise still be picked up by the runner.
rm -rf build/classes build/test-classes
mkdir -p lib build/classes build/test-classes

[ -f "$MONGO_JAR" ] || curl -sSL -o "$MONGO_JAR" \
  https://repo1.maven.org/maven2/org/mongodb/mongo-java-driver/3.12.14/mongo-java-driver-3.12.14.jar
[ -f "$JUNIT_JAR" ] || curl -sSL -o "$JUNIT_JAR" \
  https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar

javac -nowarn -d build/classes -cp "$MONGO_JAR" src/*.java
javac -nowarn -d build/test-classes -cp "build/classes:$MONGO_JAR:$JUNIT_JAR" test/*.java
java -jar "$JUNIT_JAR" \
  --class-path "build/classes:build/test-classes:$MONGO_JAR" \
  --scan-class-path --details=summary --disable-ansi-colors
