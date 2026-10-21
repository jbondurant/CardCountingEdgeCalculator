#!/bin/sh
# Builds the card-counting results page (PLAN_PAGE.md 2.4) into a directory of your choice.
#
#   site/build-site.sh --out DIR [--inputs DIR] [--index PATH] [--version V] [--date YYYY-MM-DD] [--check]
#   site/build-site.sh --fixture --out DIR      (made-up inputs from test/SiteFixture, for a look at the layout)
#
# --inputs defaults to runs/site, the JSON files the computations write; --index to the website's
# index.html beside this repo, whose colour tokens the page takes. The build refuses to write a
# page that fails its checks, and refuses a fixture build anywhere inside the website's tree.
# The page names every file from the repository's folder: that folder (the main checkout's, found
# from git, worktree or not) is passed as --root and left out of each path, and a worktree's file
# is named BRANCH:PATH.
# It never commits, pushes or copies anything into the website repository: that is a separate,
# listed step that waits for Justin's yes (PLAN_PAGE.md section 5).
set -e
cd "$(dirname "$0")/.."

MONGO_JAR=lib/mongo-java-driver-3.12.14.jar
JUNIT_JAR=lib/junit-platform-console-standalone-1.10.2.jar
INPUTS=runs/site
INDEX=../jbondurant.github.io/index.html
[ -f "$INDEX" ] || INDEX=../../../jbondurant.github.io/index.html
VERSION=draft
DATE=$(date +%Y-%m-%d)
OUT=
CHECK=
FIXTURE=
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2 ;;
    --inputs) INPUTS=$2; shift 2 ;;
    --index) INDEX=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    --date) DATE=$2; shift 2 ;;
    --check) CHECK=--check; shift ;;
    --fixture) FIXTURE=1; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ -n "$OUT" ] || { echo "usage: site/build-site.sh --out DIR [--inputs DIR] [--fixture] [--check]" >&2; exit 2; }

CLASSES=$(mktemp -d)
trap 'rm -rf "$CLASSES"' EXIT
javac -nowarn -d "$CLASSES" -cp "$MONGO_JAR" src/*.java
if [ -n "$FIXTURE" ]; then
  javac -nowarn -d "$CLASSES" -cp "$CLASSES:$MONGO_JAR:$JUNIT_JAR" test/SiteFixture.java
  INPUTS=$CLASSES/fixture-inputs
  java -Xmx1g -cp "$CLASSES:$MONGO_JAR" SiteFixture "$INPUTS"
  VERSION=fixture
fi
ROOT=$(cd "$(git rev-parse --git-common-dir)/.." && pwd)
java -Xmx1g -cp "$CLASSES:$MONGO_JAR" SiteBuild --inputs "$INPUTS" --site site --index "$INDEX" \
  --version "$VERSION" --date "$DATE" --commit "$(git rev-parse HEAD)" --out "$OUT" --root "$ROOT" $CHECK
