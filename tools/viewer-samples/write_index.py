"""Writes index.tsv for the viewer samples: group, name, MIME type and size, one sample a line.

Usage: python3 write_index.py <out dir>. The MIME type is the one the page opens the sample as,
as Moodle would send it (lib/classes/filetypes.php): from the extension, so the edge cases named
for another format keep their name's type. Fails on a file with no entry here, or an entry with
no file, so the two never drift.
"""

import sys
from pathlib import Path

OUT = Path(sys.argv[1])
DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

SAMPLES = [
    ("Video", "h264.mp4", "video/mp4"),
    ("Video", "h264.mov", "video/quicktime"),
    ("Video", "hevc-8bit.mov", "video/quicktime"),
    ("Video", "hevc-main10-1080p.mov", "video/quicktime"),
    ("Video", "hevc-main10-4k60.mov", "video/quicktime"),
    ("Video", "av1.mp4", "video/mp4"),
    ("Video", "vp9.webm", "video/webm"),
    ("Video", "h264.mkv", "video/x-matroska"),
    ("Video", "h264.3gp", "video/3gpp"),
    ("Audio", "tone.mp3", "audio/mpeg"),
    ("Audio", "tone.m4a", "audio/mp4"),
    ("Audio", "tone.ogg", "audio/ogg"),
    ("Audio", "tone.opus", "audio/ogg"),
    ("Audio", "tone.flac", "audio/flac"),
    ("Audio", "tone.wav", "audio/wav"),
    ("Images", "pattern.jpg", "image/jpeg"),
    ("Images", "pattern.png", "image/png"),
    ("Images", "animated.gif", "image/gif"),
    ("Images", "pattern.webp", "image/webp"),
    ("Images", "pattern.heic", "image/heic"),
    ("Images", "pattern.bmp", "image/bmp"),
    ("Documents", "three-pages.pdf", "application/pdf"),
    ("Documents", "document.docx", DOCX),
    ("Documents", "slides.pptx", PPTX),
    ("Documents", "utf8.txt", "text/plain"),
    ("Documents", "big5.txt", "text/plain"),
    ("Documents", "utf16.txt", "text/plain"),
    ("Documents", "notes.md", "text/markdown"),
    ("Documents", "sample.kt", "text/plain"),
    ("Archives", "archive.zip", "application/zip"),
    ("Archives", "archive.tar", "application/x-tar"),
    # Moodle's own type for .gz, misspelling and all.
    ("Archives", "archive.tar.gz", "application/g-zip"),
    ("Archives", "archive.tar.bz2", "application/x-bzip2"),
    ("Archives", "archive.tar.xz", "application/x-xz"),
    ("Archives", "readme.txt.gz", "application/g-zip"),
    ("Edge cases", "sheet.xlsx", XLSX),
    ("Edge cases", "vector.svg", "image/svg+xml"),
    ("Edge cases", "page.html", "text/html"),
    ("Edge cases", "zero-bytes.pdf", "application/pdf"),
    ("Edge cases", "truncated.mp4", "video/mp4"),
    ("Edge cases", "mp4-named.mov", "video/quicktime"),
    ("Edge cases", "mov-named.mp4", "video/mp4"),
]



def asset_name(name: str) -> str:
    """Where a sample is stored: a name ending in .gz gets .sample after it, since the Android
    Gradle plugin's asset merge takes a .gz off and unpacks the file. ViewerSamples.assetName
    keeps the same rule."""
    return f"{name}.sample" if name.endswith(".gz") else name


for _, name, _ in SAMPLES:
    if asset_name(name) != name and (OUT / name).exists():
        (OUT / name).rename(OUT / asset_name(name))

listed = {asset_name(name) for _, name, _ in SAMPLES}
present = {p.name for p in OUT.iterdir() if p.name != "index.tsv"}
if listed != present:
    sys.exit(f"index and files differ: unlisted {sorted(present - listed)}, missing {sorted(listed - present)}")

lines = ["group\tname\tmime\tbytes"]
lines += [f"{group}\t{name}\t{mime}\t{(OUT / asset_name(name)).stat().st_size}" for group, name, mime in SAMPLES]
(OUT / "index.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
