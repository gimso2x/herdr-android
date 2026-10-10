#!/usr/bin/env python3
"""Pane-area metrics of the released app from a screenshot (stdlib only).

usage: pane_metrics.py <screenshot.png>        pane metrics, exit 0 when the pane area is visible
       pane_metrics.py --screen <screenshot.png>   whole-screen ink fraction only (failure facts)

The release APK has no devtools and uiautomator cannot dump a page whose terminal never idles (its
cursor blinks: "could not get idle state"), so the pane area is judged from the pixels, which is also
what the v0.1.0/v0.1.1 bug was about (header, banner and tabs drawn over a blank pane area):

  * background_dark:  the most common colour below the header is dark (herdr's dark theme; Cloudflare's
                      login page and an unloaded WebView are light/white).
  * pane_extent_px:   vertical distance between the first and the last pixel row below the header
                      band (0.2 of the screen height) that holds ink, i.e. pixels that differ from the
                      most common colour. With a blank pane area only the key bar/tabs remain (< 100 px).
  * band_ink:         fraction of ink in the band 0.375..0.85 of the screen height, where terminal rows or
                      chat messages sit and where nothing but a blank area sits when the pane has no height.

pane_visible = pane_extent_px > 100, pane_non_blank = band_ink > 0.002. Counts and booleans only: no
pixel values and no text are printed. Exit 0 when the pane is visible, not blank and the page is dark,
1 when not, 2 on an unreadable screenshot.
"""
import collections
import struct
import sys
import zlib

MIN_EXTENT = 100
MIN_INK = 0.002
HEADER_FRACTION = 0.2
BAND = (0.375, 0.85)
DARK_LUMA = 90


def decode_png(data):
    """8-bit non-interlaced RGB(A) PNG -> (width, height, bytes per pixel, rows of bytes)."""
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


def region_stats(png, y1, y2):
    """(dominant colour, [ink pixel count per row]) over rows y1..y2-1, full width."""
    width, _, bpp, rows = png
    counts = collections.Counter()
    for y in range(y1, y2):
        row = rows[y]
        for x in range(width):
            counts[row[x * bpp:x * bpp + 3]] += 1
    dominant = counts.most_common(1)[0][0]
    ink_per_row = []
    for y in range(y1, y2):
        row = rows[y]
        ink_per_row.append(sum(1 for x in range(width) if row[x * bpp:x * bpp + 3] != dominant))
    return dominant, ink_per_row


def main():
    if len(sys.argv) < 2:
        print("usage: pane_metrics.py [--screen] <screenshot.png>")
        return 2
    screen_only = sys.argv[1] == "--screen"
    path = sys.argv[-1]
    try:
        png = decode_png(open(path, "rb").read())
    except Exception as error:  # say what failed, never echo content
        print("pane-metrics: unreadable screenshot (%s)" % type(error).__name__)
        return 2
    width, height = png[0], png[1]
    if screen_only:
        _, ink = region_stats(png, 0, height)
        print("screen-ink: width=%d height=%d ink_fraction=%.4f" % (width, height, sum(ink) / (width * height)))
        return 0
    top = int(height * HEADER_FRACTION)
    dominant, ink = region_stats(png, top, height)
    luma = (299 * dominant[0] + 587 * dominant[1] + 114 * dominant[2]) // 1000
    inked_rows = [i for i, n in enumerate(ink) if n > 0]
    extent = (inked_rows[-1] - inked_rows[0] + 1) if inked_rows else 0
    b1, b2 = int(height * BAND[0]) - top, int(height * BAND[1]) - top
    band = ink[b1:b2]
    band_ink = sum(band) / (width * len(band))
    visible, non_blank, dark = extent > MIN_EXTENT, band_ink > MIN_INK, luma < DARK_LUMA
    print("pane-metrics: pane_extent_px=%d band_ink=%.4f background_dark=%s pane_visible=%s pane_non_blank=%s" % (
        extent, band_ink, str(dark).lower(), str(visible).lower(), str(non_blank).lower()))
    return 0 if (visible and non_blank and dark) else 1


if __name__ == "__main__":
    sys.exit(main())
