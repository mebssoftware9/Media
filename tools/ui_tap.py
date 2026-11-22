#!/usr/bin/env python3
"""Prints the centre of the first on-screen element whose text or
description contains LABEL (any case), from a uiautomator dump.

    python3 tools/ui_tap.py ui.xml "Got it"   ->   540 2010

Prints nothing if there is no such element. Used by world_screenshots.sh.
"""
import re
import sys
import xml.etree.ElementTree as ET


def main():
    path, label = sys.argv[1], sys.argv[2].lower()
    try:
        root = ET.parse(path).getroot()
    except Exception:
        return
    for node in root.iter("node"):
        text = (node.get("text") or "") + " " + (node.get("content-desc") or "")
        if label in text.lower():
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                print((x1 + x2) // 2, (y1 + y2) // 2)
                return


if __name__ == "__main__":
    main()
