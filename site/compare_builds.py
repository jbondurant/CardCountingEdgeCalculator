"""Compares two builds of the page on the same inputs, to show a change renamed files and nothing else.

    site/compare-builds.sh OLD NEW ROOT INPUTS OLD_COMMIT NEW_COMMIT EXPECT

OLD and NEW are two builds' folders (card-counting/'s files), ROOT the repository's folder, INPUTS
the inputs' folder in the repository (runs/site-1946b3d), OLD_COMMIT and NEW_COMMIT the two builds'
commits in full, and EXPECT a file of the text edits the change meant to make, one JSON object a
line: {"file": "index.html", "old": "...", "new": "..."}. An edit with "all": true is a token of the
build itself, such as its date, read as NEW's everywhere it occurs in the file named ("*": every
file, the data files too), before anything is compared.

Written apart from SiteData.read, it renames OLD's files by the naming rule: ROOT's folder left out
of every path, a file of a worktree under ROOT/.worktrees named BRANCH:PATH by the branch git
worktree list gives it (.worktrees/NAME/PATH if none), runs/site/ named INPUTS/, and OLD_COMMIT
read as NEW_COMMIT, since the figures SiteBuild computes itself are credited to the commit that
built the page. Then, exiting 1 on any failure:
  1. data/results.json and data/results.csv must be byte for byte NEW's: every figure, interval,
     source and table.
  2. The two builds must hold the same files.
  3. Every other file must be NEW's exactly, once the served files' hashes and sizes and the
     manifest's hash are read as NEW's, the recorded files' rows are put in one order (they are
     sorted by name, and the names changed), and each of EXPECT's edits, found exactly once, is
     made. Whatever is left is printed in full.
  4. Each recorded file NEW names outside runs/ is found in the repository with its recorded
     SHA-256: a BRANCH:PATH name on that branch, any other name at its recorded commit, which
     must be a commit.
"""
import difflib
import hashlib
import json
import os
import re
import subprocess
import sys


def git(*args):
    p = subprocess.run(["git"] + list(args), cwd=REPO, capture_output=True)
    return p.stdout if p.returncode == 0 else None


def worktree_branches(root):
    out = {}
    listing = git("-C", root, "worktree", "list", "--porcelain")
    at = None
    for line in (listing or b"").decode().splitlines():
        if line.startswith("worktree "):
            at = os.path.normpath(line[len("worktree "):])
        elif line.startswith("branch refs/heads/") and at and os.path.dirname(at) == os.path.join(root, ".worktrees"):
            out[os.path.basename(at)] = line[len("branch refs/heads/"):]
    return out


def rename(text, root, branches, inputs, old_commit, new_commit):
    def one(m):
        w = m.group(1)
        if w is None:
            return ""
        return branches[w] + ":" if w in branches else ".worktrees/" + w + "/"
    text = re.sub(re.escape(root) + r"/(?:\.worktrees/([^/\s\"'<>\\]+)/)?", one, text)
    return text.replace("runs/site/", inputs + "/").replace(old_commit, new_commit)


def grouped(n):
    return "{:,}".format(n)


def served(folder):
    m = json.load(open(os.path.join(folder, "data/manifest.json"), encoding="utf-8"))
    return {s["path"]: s for s in m["served"]}


def sort_recorded(name, text):
    """The recorded files' rows in one order, in each of the three files that list them."""
    if name == "data/manifest.json":
        a = text.index('  "recorded": [\n') + len('  "recorded": [\n')
        b = text.index("\n  ]", a)
        rows = sorted(r.rstrip(",") for r in text[a:b].split("\n"))
        return text[:a] + "\n".join(rows) + text[b:]
    if name == "data/MANIFEST.md":
        a = text.index("## Recorded\n")
        head = text.index("|---", a)
        a = text.index("\n", head) + 1
        b = text.find("\n\n", a)
        b = len(text) if b < 0 else b
        rows = sorted(text[a:b].rstrip("\n").split("\n"))
        return text[:a] + "\n".join(rows) + text[b:]
    if name == "index.html":
        k = text.find('data-s="recorded.0.path"')
        if k < 0:
            return text
        a = text.rindex("<tbody>", 0, k) + len("<tbody>")
        b = text.index("</tbody>", k)
        rows = re.findall(r"<tr>.*?</tr>", text[a:b])
        rows = sorted(re.sub(r'data-s="recorded\.\d+\.', 'data-s="recorded.#.', r) for r in rows)
        rest = re.sub(r'data-s="recorded\.\d+\.', 'data-s="recorded.#.', text[b:])
        return text[:a] + "".join(rows) + rest
    return text


def leftover(a, b):
    """Every chunk where a and b still differ, in full: the lines that differ, then word by word within them."""
    la, lb = a.split("\n"), b.split("\n")
    out = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, la, lb, autojunk=False).get_opcodes():
        if op == "equal":
            continue
        ta = re.findall(r"\s+|[^\s<>]+|[<>]", "\n".join(la[i1:i2]))
        tb = re.findall(r"\s+|[^\s<>]+|[<>]", "\n".join(lb[j1:j2]))
        if len(ta) * len(tb) > 4e8:
            out.append("    lines %d-%d of old against %d-%d of new: too long to diff word by word" % (i1 + 1, i2, j1 + 1, j2))
            continue
        for o, x1, x2, y1, y2 in difflib.SequenceMatcher(None, ta, tb, autojunk=False).get_opcodes():
            if o != "equal":
                out.append("    line %d: - %r\n    line %d: + %r" % (i1 + 1, "".join(ta[x1:x2]), j1 + 1, "".join(tb[y1:y2])))
    return out


def main(argv):
    if len(argv) != 7:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    old, new, root, inputs, old_commit, new_commit, expect = argv
    root = os.path.normpath(os.path.abspath(root))
    branches = worktree_branches(root)
    print("worktrees named by branch: " + ", ".join("%s -> %s" % kv for kv in sorted(branches.items())))
    edits = [json.loads(l) for l in open(expect, encoding="utf-8") if l.strip()]
    status = 0

    files_old = sorted(os.path.relpath(os.path.join(d, f), old) for d, _, fs in os.walk(old) for f in fs)
    files_new = sorted(os.path.relpath(os.path.join(d, f), new) for d, _, fs in os.walk(new) for f in fs)
    if files_old != files_new:
        print("DIFFERENT FILE LISTS: only in old %s; only in new %s"
              % (sorted(set(files_old) - set(files_new)), sorted(set(files_new) - set(files_old))))
        status = 1
    else:
        print("same files: %d in each" % len(files_new))

    so, sn = served(old), served(new)
    man_old = hashlib.sha256(open(os.path.join(old, "data/manifest.json"), "rb").read()).hexdigest()
    man_new = hashlib.sha256(open(os.path.join(new, "data/manifest.json"), "rb").read()).hexdigest()

    for name in files_new:
        if name not in files_old:
            continue
        a = rename(open(os.path.join(old, name), encoding="utf-8").read(), root, branches, inputs, old_commit, new_commit)
        for e in edits:
            if e.get("all") and e["file"] in ("*", name):
                a = a.replace(e["old"], e["new"])
        b = open(os.path.join(new, name), encoding="utf-8").read()
        if name in ("data/results.json", "data/results.csv"):
            if a == b:
                print("same: %s, byte for byte, once renamed" % name)
            else:
                print("DIFFERS: %s" % name)
                for x in leftover(a, b)[:40]:
                    print(x)
                status = 1
            continue
        for path, s in so.items():
            t = sn[path]
            a = a.replace(s["sha256"], t["sha256"])
            for f in ('"bytes":%d', '"contentSize":"%d B"', "| %d |"):
                a = a.replace(f % s["bytes"], f % t["bytes"])
            a = a.replace(">%s<" % grouped(s["bytes"]), ">%s<" % grouped(t["bytes"]))
        a = a.replace(man_old, man_new)
        for e in edits:
            if e["file"] == name and not e.get("all"):
                n = a.count(e["old"])
                if n != 1:
                    print("EXPECTED EDIT NOT FOUND ONCE (%d times) in %s: %r" % (n, name, e["old"][:120]))
                    status = 1
                a = a.replace(e["old"], e["new"])
        a, b = sort_recorded(name, a), sort_recorded(name, b)
        if a == b:
            continue
        print("DIFFERS: %s, beyond the renaming, the hashes and the expected edits:" % name)
        for x in leftover(a, b):
            print(x)
        status = 1
    print("every other file the same, once its hashes are read as the new build's and the expected edits made"
          if status == 0 else "")

    print("Recorded files outside runs/, found in the repository by the name the page gives:")
    for r in json.load(open(os.path.join(new, "data/manifest.json"), encoding="utf-8"))["recorded"]:
        path, sha, commit = r["path"], r.get("sha256", ""), str(r.get("commit", ""))
        if path.startswith("runs/"):
            continue
        tries = []
        if ":" in path:
            branch, file = path.split(":", 1)
            tries = [(branch, file), ("origin/" + branch, file)]
        elif (git("cat-file", "-t", commit) or b"").strip() == b"commit":
            tries = [(commit, path)]
        found = None
        for rev, file in tries:
            if git("cat-file", "-e", "%s:%s" % (rev, file)) is not None:
                if hashlib.sha256(git("show", "%s:%s" % (rev, file))).hexdigest() == sha:
                    found = rev
                    break
        if found:
            print("found: %s at %s" % (path, found))
        else:
            print("NOT FOUND: %s (sha256 %s, recorded commit %s%s)" % (path, sha, commit,
                  "" if tries else ", which is not a commit"))
            status = 1
    return status


REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
