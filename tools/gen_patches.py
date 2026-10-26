# -*- coding: utf-8 -*-
"""Generate anchor-based patch ops (base smali -> patched smali) as JSON.

Each op stores an exact line window from the base file and its replacement.
The window is grown until it occurs exactly once in that file, so the applier
can never patch the wrong place.
"""
import difflib
import json
import os
import sys

FILES = [
    r"c\c\a\s2.smali",
    r"c\c\a\v3\g0.smali",
    r"c\c\a\v3\i0\d.smali",
    r"c\c\a\v3\i0\q.smali",
    r"c\c\a\v3\k0\t.smali",
    r"com\didjdk\adbhelper\MyApplication.smali",
]

MAX_GROW = 40


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read().split("\n")


def count(lines, window):
    return sum(1 for i in range(len(lines) - len(window) + 1)
               if lines[i:i + len(window)] == window)


def build_ops(base, final):
    sm = difflib.SequenceMatcher(None, base, final, autojunk=False)
    ops = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        grow = 1
        while True:
            s = max(0, i1 - grow)
            e = min(len(base), i2 + grow)
            js = max(0, j1 - (i1 - s))
            je = min(len(final), j2 + (e - i2))
            find, replace = base[s:e], final[js:je]
            if count(base, find) == 1 or grow >= MAX_GROW:
                break
            grow += 1
        ops.append({"find": find, "replace": replace})
    return ops


def main():
    base_dir, final_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]
    data = {"files": []}
    for rel in FILES:
        bp = os.path.join(base_dir, rel)
        fp = os.path.join(final_dir, rel)
        ops = build_ops(read(bp), read(fp))
        if not ops:
            raise SystemExit("no diff for " + rel)
        data["files"].append({"path": rel.replace("\\", "/"), "ops": ops})
        print("%-45s %d op(s)" % (rel, len(ops)))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main()
