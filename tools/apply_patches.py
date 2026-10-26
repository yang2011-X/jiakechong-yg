# -*- coding: utf-8 -*-
"""Apply the anchor-based smali patches produced by tools/gen_patches.py.

Usage: python apply_patches.py <decoded-dir> [patch.json]

Every anchor must match exactly once, so a wrong or already-patched tree fails
loudly instead of producing a silently broken APK.
"""
import json
import os
import sys


def apply_file(path, ops):
    with open(path, "r", encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")
    for n, op in enumerate(ops, 1):
        find, replace = op["find"], op["replace"]
        hits = [i for i in range(len(lines) - len(find) + 1)
                if lines[i:i + len(find)] == find]
        if len(hits) != 1:
            raise SystemExit("%s: op %d matched %d times (expected 1)"
                             % (path, n, len(hits)))
        i = hits[0]
        lines[i:i + len(find)] = replace
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(lines))


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    root = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    patch = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        here, "..", "patches", "beetle-android16.json")
    with open(patch, "r", encoding="utf-8") as f:
        data = json.load(f)

    for entry in data["files"]:
        path = os.path.join(root, entry["path"].replace("/", os.sep))
        if not os.path.exists(path):
            raise SystemExit("missing " + path)
        apply_file(path, entry["ops"])
        print("patched %-45s %d op(s)" % (entry["path"], len(entry["ops"])))


if __name__ == "__main__":
    main()
