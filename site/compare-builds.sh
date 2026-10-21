#!/bin/sh
# Compares a build of the page from before SiteBuild named files by their path in the repository
# (20 October 2026) with one from after, on the same inputs, to show the change renamed files and
# nothing else.
#
#   site/compare-builds.sh OLD NEW ROOT INPUTS OLD_COMMIT NEW_COMMIT
#
# OLD and NEW are two builds' folders (card-counting/'s files), ROOT the repository's folder,
# INPUTS the inputs' folder in the repository (runs/site-1946b3d), and OLD_COMMIT and NEW_COMMIT
# the two builds' commits in full: the figures SiteBuild computes itself (counts, ranges) are
# credited to the commit that built the page. Apart from SiteData.read, it applies the naming rule
# to OLD's files: ROOT's folder, and a worktree's folder under ROOT/.worktrees, left out of every
# path; runs/site/ named INPUTS/; OLD_COMMIT read as NEW_COMMIT. Then:
#   1. data/results.json and data/results.csv must be byte for byte the same as NEW's: every
#      figure, interval, source and table. It exits 1 if not.
#   2. Every other difference is printed, for a person to read: the hashes that change with them,
#      the manifest's text, the template's own changes.
#   3. Each recorded file NEW names outside runs/ is looked up in the repository: at its recorded
#      commit, else at the tips of site-integration and custom-count-design. Its SHA-256 must be
#      the one recorded, so the name a reader is given finds the file the figures were read from.
set -e
[ $# -eq 6 ] || { echo "usage: site/compare-builds.sh OLD NEW ROOT INPUTS OLD_COMMIT NEW_COMMIT" >&2; exit 2; }
OLD=$1; NEW=$2; ROOT=$3; INPUTS=$4; OLDC=$5; NEWC=$6
cd "$(dirname "$0")/.."
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
cp -R "$OLD" "$T/old"
find "$T/old" -type f | while read -r f; do
  ROOT="$ROOT" INPUTS="$INPUTS" OLDC="$OLDC" NEWC="$NEWC" perl -pi -e \
    's{\Q$ENV{ROOT}\E/(\.worktrees/[^/\s"'"'"'<>]+/)?}{}g; s{runs/site/}{$ENV{INPUTS}/}g; s{\Q$ENV{OLDC}\E}{$ENV{NEWC}}g' "$f"
done
status=0
for f in data/results.json data/results.csv; do
  if cmp -s "$T/old/$f" "$NEW/$f"; then
    echo "same: $f, byte for byte, once the old build's paths are renamed"
  else
    echo "DIFFERS: $f"; diff "$T/old/$f" "$NEW/$f" | head -20; status=1
  fi
done
echo
echo "Other files that differ once renamed, with their changed lines:"
(cd "$T/old" && find . -type f | sort) > "$T/old.list"
(cd "$NEW" && find . -type f | sort) > "$T/new.list"
if ! cmp -s "$T/old.list" "$T/new.list"; then
  echo "DIFFERENT FILE LISTS:"; diff "$T/old.list" "$T/new.list"; status=1
fi
while read -r f; do
  case "$f" in ./data/results.json|./data/results.csv) continue ;; esac
  [ -f "$NEW/$f" ] || continue
  if ! cmp -s "$T/old/$f" "$NEW/$f"; then
    echo "== $f"
    diff "$T/old/$f" "$NEW/$f" | grep '^[<>]' | cut -c1-400 || true
  fi
done < "$T/old.list"
echo
echo "Recorded files outside runs/, found in the repository by the name the page gives:"
python3 - "$NEW/data/manifest.json" <<'EOF' > "$T/recorded.tsv"
import json, sys
m = json.load(open(sys.argv[1]))
for a in m["recorded"]:
    if not a["path"].startswith("runs/"):
        print("\t".join([a["path"], a.get("sha256", ""), str(a.get("commit", ""))]))
EOF
while IFS="$(printf '\t')" read -r path sha commit; do
  found=
  for rev in "$commit" origin/site-integration origin/custom-count-design; do
    got=$(git show "$rev:$path" 2>/dev/null | shasum -a 256 | cut -d' ' -f1) || got=
    if [ "$got" = "$sha" ]; then found=$rev; break; fi
  done
  if [ -n "$found" ]; then
    echo "found: $path at $found"
  else
    echo "NOT FOUND: $path (sha256 $sha, commit $commit)"; status=1
  fi
done < "$T/recorded.tsv"
exit $status
