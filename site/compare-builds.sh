#!/bin/sh
# Compares two builds of the page on the same inputs, to show a change renamed files and nothing
# else (20 October 2026, when SiteBuild began naming files from the repository's folder). The
# checks and their rules are in site/compare_builds.py.
#
#   site/compare-builds.sh OLD NEW ROOT INPUTS OLD_COMMIT NEW_COMMIT EXPECT
exec python3 -I "$(dirname "$0")/compare_builds.py" "$@"
