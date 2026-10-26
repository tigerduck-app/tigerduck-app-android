"""Writes the documents, text files, archives and web files among the viewer samples.

Usage: python3 make_documents.py <out dir>. make_samples.sh runs it after the images, which the
archives carry one of. Everything here is written by hand: a PDF, Word, PowerPoint and Excel
file with just enough parts to open, and our own text.
"""

import gzip
import io
import sys
import tarfile
import zipfile
from pathlib import Path

OUT = Path(sys.argv[1])


def write_pdf(path: Path, pages: int) -> None:
    """A PDF of [pages] pages, each naming itself, in Helvetica, drawn by hand."""
    objects = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)

    catalog = add(b"")  # Filled in once the page tree's number is known.
    tree = add(b"")
    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    kids = []
    for n in range(1, pages + 1):
        text = f"Page {n} of {pages}".encode()
        stream = (
            b"0.85 0.9 1 rg 36 36 540 720 re f 0 0 0 rg BT /F1 36 Tf 72 680 Td ("
            + text
            + b") Tj 0 -48 Td /F1 16 Tf (A TigerDuck viewer sample.) Tj ET"
        )
        content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        kids.append(add(
            b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>"
            % (tree, font, content)
        ))
    objects[catalog - 1] = b"<< /Type /Catalog /Pages %d 0 R >>" % tree
    objects[tree - 1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (
        b" ".join(b"%d 0 R" % k for k in kids), len(kids))

    out = io.BytesIO()
    out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for number, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % number + body + b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1))
    for offset in offsets:
        out.write(b"%010d 00000 n \n" % offset)
    out.write(b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog, xref))
    path.write_bytes(out.getvalue())


def write_zip(path: Path, parts: dict) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)


XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
DOC_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
CT_RELS = "application/vnd.openxmlformats-package.relationships+xml"


def content_types(overrides: dict) -> str:
    return XML + (
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        f'<Default Extension="rels" ContentType="{CT_RELS}"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        + "".join(f'<Override PartName="{p}" ContentType="{t}"/>' for p, t in overrides.items())
        + "</Types>"
    )


def relationships(rels: list) -> str:
    return XML + f'<Relationships xmlns="{RELS}">' + "".join(
        f'<Relationship Id="{i}" Type="{DOC_RELS}/{t}" Target="{target}"/>' for i, t, target in rels
    ) + "</Relationships>"


def write_docx(path: Path) -> None:
    w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    def para(text: str, bold: bool = False, size: int = 0) -> str:
        props = ("<w:b/>" if bold else "") + (f'<w:sz w:val="{size}"/>' if size else "")
        return f'<w:p><w:r>{"<w:rPr>" + props + "</w:rPr>" if props else ""}<w:t xml:space="preserve">{text}</w:t></w:r></w:p>'

    cells = "".join(
        "<w:tr>" + "".join(f"<w:tc><w:p><w:r><w:t>{c}</w:t></w:r></w:p></w:tc>" for c in row) + "</w:tr>"
        for row in [("Period", "Course"), ("1", "Data Structures"), ("2", "Operating Systems")]
    )
    body = (
        para("TigerDuck viewer sample", bold=True, size=40)
        + para("A Word document written by hand: a heading, two paragraphs and a table.")
        + para("中文段落：這是一份用來測試檢視器的 Word 文件。")
        + f'<w:tbl><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/><w:bottom w:val="single" w:sz="4"/>'
        f'<w:insideH w:val="single" w:sz="4"/></w:tblBorders></w:tblPr>{cells}</w:tbl>'
    )
    write_zip(path, {
        "[Content_Types].xml": content_types({
            "/word/document.xml": "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
        }),
        "_rels/.rels": relationships([("rId1", "officeDocument", "word/document.xml")]),
        # An A4 page with inch margins: with no size given, a renderer may lay it out tiny.
        "word/document.xml": XML + (
            f'<w:document xmlns:w="{w}"><w:body>{body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>'
        ),
    })


P = "http://schemas.openxmlformats.org/presentationml/2006/main"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
NS = f'xmlns:a="{A}" xmlns:r="{DOC_RELS}" xmlns:p="{P}"'
EMPTY_TREE = (
    '<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
    '<p:grpSpPr/>{shapes}</p:spTree>'
)


def text_box(shape_id: int, text: str, y: int, size: int) -> str:
    return (
        f'<p:sp><p:nvSpPr><p:cNvPr id="{shape_id}" name="Text {shape_id}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
        f'<p:spPr><a:xfrm><a:off x="457200" y="{y}"/><a:ext cx="8229600" cy="914400"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>'
        f'<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="{size}"/><a:t>{text}</a:t></a:r></a:p></p:txBody></p:sp>'
    )


def theme() -> str:
    colors = "".join(
        f"<a:{name}><a:srgbClr val=\"{value}\"/></a:{name}>"
        for name, value in [
            ("dk1", "000000"), ("lt1", "FFFFFF"), ("dk2", "1F2937"), ("lt2", "F3F4F6"), ("accent1", "2563EB"),
            ("accent2", "F97316"), ("accent3", "16A34A"), ("accent4", "9333EA"), ("accent5", "DC2626"),
            ("accent6", "0891B2"), ("hlink", "2563EB"), ("folHlink", "7C3AED"),
        ]
    )
    fill = '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>'
    line = '<a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>'
    effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
    return XML + (
        f'<a:theme xmlns:a="{A}" name="Sample"><a:themeElements>'
        f'<a:clrScheme name="Sample">{colors}</a:clrScheme>'
        '<a:fontScheme name="Sample"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>'
        '<a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>'
        f'<a:fmtScheme name="Sample"><a:fillStyleLst>{fill * 3}</a:fillStyleLst><a:lnStyleLst>{line * 3}</a:lnStyleLst>'
        f'<a:effectStyleLst>{effect * 3}</a:effectStyleLst><a:bgFillStyleLst>{fill * 3}</a:bgFillStyleLst></a:fmtScheme>'
        "</a:themeElements></a:theme>"
    )


def write_pptx(path: Path) -> None:
    pml = "application/vnd.openxmlformats-officedocument.presentationml"
    slides = [
        ("TigerDuck viewer sample", "Slide 1 of 2, written by hand."),
        ("第二張投影片", "Slide 2 of 2: 中文文字。"),
    ]
    parts = {
        "[Content_Types].xml": content_types({
            "/ppt/presentation.xml": f"{pml}.presentation.main+xml",
            "/ppt/slideMasters/slideMaster1.xml": f"{pml}.slideMaster+xml",
            "/ppt/slideLayouts/slideLayout1.xml": f"{pml}.slideLayout+xml",
            "/ppt/theme/theme1.xml": "application/vnd.openxmlformats-officedocument.theme+xml",
            **{f"/ppt/slides/slide{n}.xml": f"{pml}.slide+xml" for n in range(1, len(slides) + 1)},
        }),
        "_rels/.rels": relationships([("rId1", "officeDocument", "ppt/presentation.xml")]),
        "ppt/presentation.xml": XML + (
            f"<p:presentation {NS}>"
            '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
            "<p:sldIdLst>"
            + "".join(f'<p:sldId id="{255 + n}" r:id="rId{n + 1}"/>' for n in range(1, len(slides) + 1))
            + "</p:sldIdLst>"
            '<p:sldSz cx="9144000" cy="5143500"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>'
        ),
        "ppt/_rels/presentation.xml.rels": relationships(
            [("rId1", "slideMaster", "slideMasters/slideMaster1.xml")]
            + [(f"rId{n + 1}", "slide", f"slides/slide{n}.xml") for n in range(1, len(slides) + 1)]
            + [(f"rId{len(slides) + 2}", "theme", "theme/theme1.xml")]
        ),
        "ppt/slideMasters/slideMaster1.xml": XML + (
            f"<p:sldMaster {NS}><p:cSld>{EMPTY_TREE.format(shapes='')}</p:cSld>"
            '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" '
            'accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>'
            '<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>'
        ),
        "ppt/slideMasters/_rels/slideMaster1.xml.rels": relationships(
            [("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"), ("rId2", "theme", "../theme/theme1.xml")]
        ),
        "ppt/slideLayouts/slideLayout1.xml": XML + (
            f'<p:sldLayout {NS} type="blank"><p:cSld name="Blank">{EMPTY_TREE.format(shapes="")}</p:cSld>'
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
        ),
        "ppt/slideLayouts/_rels/slideLayout1.xml.rels": relationships(
            [("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")]
        ),
        "ppt/theme/theme1.xml": theme(),
    }
    for n, (title, line) in enumerate(slides, start=1):
        shapes = text_box(2, title, 914400, 4000) + text_box(3, line, 2286000, 2400)
        parts[f"ppt/slides/slide{n}.xml"] = XML + (
            f"<p:sld {NS}><p:cSld>{EMPTY_TREE.format(shapes=shapes)}</p:cSld>"
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
        )
        parts[f"ppt/slides/_rels/slide{n}.xml.rels"] = relationships(
            [("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")]
        )
    write_zip(path, parts)


def write_xlsx(path: Path) -> None:
    sml = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    rows = [("Period", "Course"), ("1", "Data Structures"), ("2", "Operating Systems")]
    cells = "".join(
        f'<row r="{r}">' + "".join(
            f'<c r="{chr(65 + c)}{r}" t="inlineStr"><is><t>{value}</t></is></c>' for c, value in enumerate(row)
        ) + "</row>"
        for r, row in enumerate(rows, start=1)
    )
    write_zip(path, {
        "[Content_Types].xml": content_types({
            "/xl/workbook.xml": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "/xl/worksheets/sheet1.xml": "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml",
        }),
        "_rels/.rels": relationships([("rId1", "officeDocument", "xl/workbook.xml")]),
        "xl/workbook.xml": XML + (
            f'<workbook xmlns="{sml}" xmlns:r="{DOC_RELS}"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>'
        ),
        "xl/_rels/workbook.xml.rels": relationships([("rId1", "worksheet", "worksheets/sheet1.xml")]),
        "xl/worksheets/sheet1.xml": XML + f'<worksheet xmlns="{sml}"><sheetData>{cells}</sheetData></worksheet>',
    })


TEXT = "TigerDuck viewer sample.\n臺灣科技大學 課表與作業。\nLine three: 0123456789.\n"
MARKDOWN = """# TigerDuck viewer sample

A Markdown file written by hand.

## A list

- Data Structures
- Operating Systems
  - nested item

## A table

| Period | Course |
|-------:|--------|
| 1 | Data Structures |
| 2 | 作業系統 |

```kotlin
fun main() = println("Hello")
```

> A quote, *emphasis*, **bold** and `code`.
"""
KOTLIN = """package sample

/** A code sample for the text viewer: long lines wrap, tabs stay. */
fun fibonacci(n: Int): Long {
\tvar (a, b) = 0L to 1L
\trepeat(n) { val next = a + b; a = b; b = next }
\treturn a
}

fun main() {
    println((0..10).joinToString(", ") { fibonacci(it).toString() } + " — and a line long enough to need wrapping on a phone screen.")
}
"""
SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120" viewBox="0 0 120 120">
  <rect width="120" height="120" fill="#2563eb"/>
  <circle cx="60" cy="60" r="36" fill="#f97316"/>
</svg>
"""
HTML = """<!doctype html>
<html><head><meta charset="utf-8"><title>TigerDuck viewer sample</title></head>
<body><h1>A web page</h1><p>Goes to the share sheet, never into a viewer.</p></body></html>
"""

write_pdf(OUT / "three-pages.pdf", 3)
write_docx(OUT / "document.docx")
write_pptx(OUT / "slides.pptx")
write_xlsx(OUT / "sheet.xlsx")
(OUT / "utf8.txt").write_text(TEXT, encoding="utf-8")
(OUT / "big5.txt").write_bytes(TEXT.encode("big5"))
(OUT / "utf16.txt").write_bytes(TEXT.encode("utf-16"))  # With its byte order mark.
(OUT / "notes.md").write_text(MARKDOWN, encoding="utf-8")
(OUT / "sample.kt").write_text(KOTLIN, encoding="utf-8")
(OUT / "vector.svg").write_text(SVG, encoding="utf-8")
(OUT / "page.html").write_text(HTML, encoding="utf-8")

# The archives all hold the same few files, a folder, a Chinese name and an image among them.
members = {
    "readme.txt": TEXT.encode(),
    "folder/notes.md": MARKDOWN.encode(),
    "folder/nested/pattern.png": (OUT / "pattern.png").read_bytes(),
    "中文名稱.txt": TEXT.encode(),
}
with zipfile.ZipFile(OUT / "archive.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for name, data in members.items():
        z.writestr(name, data)


def write_tar(path: Path, mode: str) -> None:
    with tarfile.open(path, mode, format=tarfile.PAX_FORMAT) as t:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mtime = 1_760_000_000
            t.addfile(info, io.BytesIO(data))


write_tar(OUT / "archive.tar", "w")
write_tar(OUT / "archive.tar.gz", "w:gz")
write_tar(OUT / "archive.tar.bz2", "w:bz2")
write_tar(OUT / "archive.tar.xz", "w:xz")
with open(OUT / "readme.txt.gz", "wb") as raw, gzip.GzipFile("readme.txt", "wb", fileobj=raw, mtime=0) as g:
    g.write(TEXT.encode())
