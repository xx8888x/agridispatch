# -*- coding: utf-8 -*-
"""把專案內的 Markdown 文件轉成同名 .docx（python-docx）。

用法：
    python tools/md2docx.py <file.md> [<file2.md> ...]
    python tools/md2docx.py --all          # 轉換專案內所有 .md（略過 Software/ 與 tools/）

支援：# 標題、段落、**粗體**、`程式碼`、- / * 項目、1. 編號、> 引言、| 表格 |、``` 程式碼區塊、--- 分隔線。
mermaid 區塊會以等寬文字保留（Word 無法繪製）。
"""
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor

ROOT = Path(__file__).resolve().parent.parent
FONT = "Microsoft JhengHei"
MONO = "Consolas"

INLINE = re.compile(r"(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\))")


def set_font(run, name=FONT, size=None, bold=None, color=None):
    run.font.name = name
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = rpr.makeelement(qn("w:rFonts"), {})
        rpr.append(rfonts)
    rfonts.set(qn("w:eastAsia"), FONT if name != MONO else MONO)
    if size:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color:
        run.font.color.rgb = RGBColor.from_string(color)


def add_inline(par, text, size=None, bold=False):
    text = text.replace("<br>", "\n").replace("<br/>", "\n")
    for part in INLINE.split(text):
        if not part:
            continue
        if part.startswith("**") and part.endswith("**"):
            set_font(par.add_run(part[2:-2]), size=size, bold=True)
        elif part.startswith("`") and part.endswith("`"):
            set_font(par.add_run(part[1:-1]), name=MONO, size=size, bold=bold, color="9C2F2F")
        elif part.startswith("["):
            m = re.match(r"\[([^\]]+)\]\(([^)]+)\)", part)
            set_font(par.add_run(m.group(1)), size=size, bold=bold, color="1F5FA8")
        else:
            set_font(par.add_run(part), size=size, bold=bold)


def split_row(line):
    cells = line.strip().strip("|").split("|")
    return [c.strip() for c in cells]


def convert(md_path: Path):
    lines = md_path.read_text(encoding="utf-8").splitlines()
    doc = Document()
    normal = doc.styles["Normal"]
    normal.font.name = FONT
    normal.font.size = Pt(11)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)

    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            i += 1
            block = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                block.append(lines[i])
                i += 1
            p = doc.add_paragraph()
            set_font(p.add_run("\n".join(block)), name=MONO, size=9)
            i += 1
            continue

        if stripped.startswith("|") and i + 1 < len(lines) and re.match(r"^\s*\|?\s*:?-{2,}", lines[i + 1]):
            header = split_row(stripped)
            rows = []
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            table = doc.add_table(rows=1, cols=len(header))
            table.style = "Table Grid"
            table.alignment = WD_TABLE_ALIGNMENT.CENTER
            for c, h in enumerate(header):
                cell = table.rows[0].cells[c]
                cell.paragraphs[0].text = ""
                add_inline(cell.paragraphs[0], h, size=10, bold=True)
            for r in rows:
                cells = table.add_row().cells
                for c in range(len(header)):
                    cells[c].paragraphs[0].text = ""
                    add_inline(cells[c].paragraphs[0], r[c] if c < len(r) else "", size=10)
            doc.add_paragraph()
            continue

        m = re.match(r"^(#{1,6})\s+(.*)", stripped)
        if m:
            level = min(len(m.group(1)), 4)
            h = doc.add_heading(level=0 if level == 1 else level - 1)
            add_inline(h, m.group(2).replace("**", ""))
        elif re.match(r"^(-{3,}|\*{3,})$", stripped):
            doc.add_paragraph("─" * 30)
        elif re.match(r"^\s*[-*+]\s+", line):
            indent = (len(line) - len(line.lstrip())) // 2
            p = doc.add_paragraph(style="List Bullet 2" if indent else "List Bullet")
            add_inline(p, re.sub(r"^\s*[-*+]\s+", "", line))
        elif re.match(r"^\s*\d+[.)]\s+", line):
            p = doc.add_paragraph(style="List Number")
            add_inline(p, re.sub(r"^\s*\d+[.)]\s+", "", line))
        elif stripped.startswith(">"):
            p = doc.add_paragraph(style="Intense Quote")
            add_inline(p, stripped.lstrip("> "))
        elif stripped:
            add_inline(doc.add_paragraph(), stripped)
        i += 1

    out = md_path.with_suffix(".docx")
    doc.save(out)
    return out


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--all"]:
        targets = [p for p in ROOT.rglob("*.md")
                   if "Software" not in p.parts and "tools" not in p.parts and ".git" not in p.parts]
    else:
        targets = [Path(a).resolve() for a in args]
    for t in targets:
        print("OK", convert(t))
