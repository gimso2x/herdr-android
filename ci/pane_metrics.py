#!/usr/bin/env python3
"""Pane-area metrics of the released app from a uiautomator dump and a screenshot (stdlib only).

usage: pane_metrics.py <ui.xml> <screenshot.png> [--shape]
       pane_metrics.py --screen <screenshot.png>

The release APK has no devtools, so the DOM cannot be read. What the smoke can see is the Android
accessibility tree of the WebView (bounds) and the pixels. A pane area exists when some node under
the WebView is tall (> MIN_HEIGHT px) and wide, is not the page-level container, and its rectangle is
not blank: more than MIN_INK of its screenshot pixels differ from its most common colour (terminal rows
or chat messages are drawn there). Prints counts and booleans only, never text or pixels; --shape adds
one `class bounds text-length desc-length` line per WebView descendant (lengths only).
Exit 0 when the pane area is visible and not blank, 1 when not, 2 on a malformed input.
"""
import collections
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

MIN_HEIGHT = 100
MIN_WIDTH = 200
MIN_INK = 0.002
PAGE_CONTAINER_FRACTION = 0.9


def box(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def decode_png(data):
    """RGB(A) 8-bit non-interlaced PNG -> (width, height, rows of (r, g, b) tuples as bytes triples)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, header = 8, b"", None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
    width, height, depth, ctype, _, _, interlace = header
    if depth != 8 or ctype not in (2, 6) or interlace:
        raise ValueError("unsupported PNG layout")
    bpp = 3 if ctype == 2 else 4
    stride = width * bpp
    raw = zlib.decompress(idat)
    rows, prev = [], bytearray(stride)
    for y in range(height):
        base = y * (stride + 1)
        ft = raw[base]
        line = bytearray(raw[base + 1:base + 1 + stride])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ft == 1:
                line[i] = (line[i] + a) & 255
            elif ft == 2:
                line[i] = (line[i] + b) & 255
            elif ft == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif ft == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if pa <= pb and pa <= pc else b if pb <= pc else c
                line[i] = (line[i] + pred) & 255
        rows.append(bytes(line))
        prev = line
    return width, height, bpp, rows


def ink_fraction(png, rect):
    width, height, bpp, rows = png
    x1, y1, x2, y2 = rect
    x1, y1, x2, y2 = max(0, x1), max(0, y1), min(width, x2), min(height, y2)
    if x2 <= x1 or y2 <= y1:
        return 0.0
    counts = collections.Counter()
    for y in range(y1, y2):
        row = rows[y]
        for x in range(x1, x2):
            counts[row[x * bpp:x * bpp + 3]] += 1
    total = sum(counts.values())
    return 1.0 - counts.most_common(1)[0][1] / total


def screen_ink(png_path):
    """Whole-screen ink fraction (failure facts: is anything drawn at all?)."""
    png = decode_png(open(png_path, "rb").read())
    print("screen-ink: width=%d height=%d ink_fraction=%.4f" % (png[0], png[1], ink_fraction(png, (0, 0, png[0], png[1]))))
    return 0


def main():
    if sys.argv[1] == "--screen":
        return screen_ink(sys.argv[2])
    ui_path, png_path = sys.argv[1], sys.argv[2]
    shape = "--shape" in sys.argv[3:]
    try:
        root = ET.parse(ui_path).getroot()
        png = decode_png(open(png_path, "rb").read())
    except Exception as error:  # malformed input: say so without echoing content
        print("pane-metrics: unreadable input (%s)" % type(error).__name__)
        return 2
    webviews = [n for n in root.iter("node") if n.get("class") == "android.webkit.WebView"]
    if not webviews:
        print("pane-metrics: no WebView node")
        return 1
    wv = webviews[0]
    wvbox = box(wv)
    wv_height = wvbox[3] - wvbox[1]
    below = [d for d in wv.iter("node") if d is not wv and box(d)]
    tall = []
    for d in below:
        x1, y1, x2, y2 = box(d)
        if y2 - y1 > MIN_HEIGHT and x2 - x1 >= MIN_WIDTH and (y2 - y1) < PAGE_CONTAINER_FRACTION * wv_height:
            tall.append(d)
        if shape:
            print("shape %s [%d,%d][%d,%d] t=%d d=%d" % (d.get("class"), x1, y1, x2, y2, len(d.get("text", "")), len(d.get("content-desc", ""))))
    best_ink, best_height = 0.0, 0
    for d in tall:
        rect = box(d)
        ink = ink_fraction(png, rect)
        if ink > best_ink:
            best_ink, best_height = ink, rect[3] - rect[1]
    ok = bool(tall) and best_ink > MIN_INK
    print("pane-metrics: webview_nodes=%d tall_nodes=%d tall_with_ink_height=%d ink_fraction=%.4f pane_visible=%s pane_non_blank=%s" % (
        len(below), len(tall), best_height, best_ink, str(bool(tall)).lower(), str(best_ink > MIN_INK).lower()))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
