# -*- coding: utf-8 -*-
"""把專案內的 Markdown 文件轉成同名 .docx（python-docx）。

用法：
    python 文件/tools/md2docx.py <file.md> [<file2.md> ...]
    python 文件/tools/md2docx.py --all     # 轉換「文件」資料夾內所有 .md（略過 tools/）

支援：# 標題、![圖說](相對路徑.png) 圖片、段落、**粗體**、`程式碼`、- / * 項目、1. 編號、> 引言、| 表格 |、``` 程式碼區塊、--- 分隔線。
mermaid 區塊會以等寬文字保留（Word 無法繪製）。
"""
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

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
        elif part.startswith("[") and re.match(r"\[([^\]]+)\]\(([^)]+)\)", part):
            m = re.match(r"\[([^\]]+)\]\(([^)]+)\)", part)
            set_font(par.add_run(m.group(1)), size=size, bold=bold, color="1F5FA8")
        else:
            set_font(par.add_run(part), size=size, bold=bold)


def split_row(line):
    cells = line.strip().strip("|").split("|")
    return [c.strip() for c in cells]


def set_cell_shading(cell, fill_hex):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = tcPr.makeelement(qn("w:shd"), {
        qn("w:val"): "clear",
        qn("w:color"): "auto",
        qn("w:fill"): fill_hex,
    })
    tcPr.append(shd)


def set_cell_margins(cell, top=30, bottom=30, left=70, right=70):
    tcPr = cell._tc.get_or_add_tcPr()
    tcMar = tcPr.makeelement(qn("w:tcMar"), {})
    for side, val in (("top", top), ("bottom", bottom), ("left", left), ("right", right)):
        node = tcMar.makeelement(qn(f"w:{side}"), {qn("w:w"): str(val), qn("w:type"): "dxa"})
        tcMar.append(node)
    tcPr.append(tcMar)


def set_cell_width_cm(cell, cm_val):
    dxa = int(round(cm_val * 567))
    tcPr = cell._tc.get_or_add_tcPr()
    tcW = tcPr.find(qn("w:tcW"))
    if tcW is None:
        tcW = tcPr.makeelement(qn("w:tcW"), {})
        tcPr.append(tcW)
    tcW.set(qn("w:w"), str(dxa))
    tcW.set(qn("w:type"), "dxa")


def tighten_p(p, before=0, after=1.0, exact_line_pt=None, no_grid=True):
    pf = p.paragraph_format
    pf.space_before = Pt(before)
    pf.space_after = Pt(after)
    if exact_line_pt:
        pf.line_spacing = Pt(exact_line_pt)
    if no_grid and hasattr(p, "_p"):
        pPr = p._p.get_or_add_pPr()
        snap = pPr.find(qn("w:snapToGrid"))
        if snap is None:
            snap = pPr.makeelement(qn("w:snapToGrid"), {qn("w:val"): "0"})
            pPr.append(snap)
        else:
            snap.set(qn("w:val"), "0")


def convert(md_path: Path):
    lines = md_path.read_text(encoding="utf-8").splitlines()
    one_page = any(l.strip() == "<!-- layout: one-page -->" for l in lines[:3])

    doc = Document()
    normal = doc.styles["Normal"]
    normal.font.name = FONT
    normal.font.size = Pt(7.5 if one_page else 11)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)

    if one_page:
        sec = doc.sections[0]
        sec.page_width = Cm(21.0)
        sec.page_height = Cm(29.7)
        sec.top_margin = Cm(0.45)
        sec.bottom_margin = Cm(0.40)
        sec.left_margin = Cm(0.85)
        sec.right_margin = Cm(0.85)
        docGrid = sec._sectPr.find(qn("w:docGrid"))
        if docGrid is not None:
            sec._sectPr.remove(docGrid)
        tighten_p(normal, 0, 1, 9.5, no_grid=False)
        normal_pPr = normal.element.get_or_add_pPr()
        normal_pPr.append(normal_pPr.makeelement(qn("w:snapToGrid"), {qn("w:val"): "0"}))

    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("<!--") and stripped.endswith("-->"):
            i += 1
            continue

        if stripped.startswith("```"):
            i += 1
            block = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                block.append(lines[i])
                i += 1
            p = doc.add_paragraph()
            set_font(p.add_run("\n".join(block)), name=MONO, size=8 if one_page else 9)
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
            col_widths_cm = [2.65, 6.75, 6.75, 3.15] if (one_page and len(header) == 4) else None
            if one_page and col_widths_cm:
                table.autofit = False
                tblPr = table._tbl.tblPr
                tblLayout = tblPr.makeelement(qn("w:tblLayout"), {qn("w:type"): "fixed"})
                tblPr.append(tblLayout)
                tblGrid = table._tbl.find(qn("w:tblGrid"))
                if tblGrid is not None:
                    for gc, w_cm in zip(tblGrid.findall(qn("w:gridCol")), col_widths_cm):
                        gc.set(qn("w:w"), str(int(round(w_cm * 567))))
            for c, h in enumerate(header):
                cell = table.rows[0].cells[c]
                cell.paragraphs[0].text = ""
                if one_page:
                    set_cell_shading(cell, "0F172A")
                    set_cell_margins(cell, top=14, bottom=14, left=45, right=45)
                    tighten_p(cell.paragraphs[0], 0, 0, 9.0)
                    add_inline(cell.paragraphs[0], h, size=7.2, bold=True)
                    for run in cell.paragraphs[0].runs:
                        run.font.color.rgb = RGBColor.from_string("FFFFFF")
                    if col_widths_cm:
                        set_cell_width_cm(cell, col_widths_cm[c])
                else:
                    add_inline(cell.paragraphs[0], h, size=10, bold=True)
            for r_idx, r in enumerate(rows):
                cells = table.add_row().cells
                for c in range(len(header)):
                    cells[c].paragraphs[0].text = ""
                    if one_page:
                        if c == 0:
                            set_cell_shading(cells[c], "F1F5F9")
                        elif r_idx % 2 == 1:
                            set_cell_shading(cells[c], "F8FAFC")
                        set_cell_margins(cells[c], top=12, bottom=12, left=45, right=45)
                        tighten_p(cells[c].paragraphs[0], 0, 0, 8.6)
                        add_inline(cells[c].paragraphs[0], r[c] if c < len(r) else "", size=6.8)
                        if col_widths_cm:
                            set_cell_width_cm(cells[c], col_widths_cm[c])
                    else:
                        add_inline(cells[c].paragraphs[0], r[c] if c < len(r) else "", size=10)
            if not one_page:
                doc.add_paragraph()
            continue

        img = re.match(r"^!\[([^\]]*)\]\(([^)]+)\)$", stripped)
        if img:
            # 圖片（相對於 md 檔位置）；寬度 16cm 符合 A4 版心（one_page 為 18.8cm），下方加圖說
            src = (md_path.parent / img.group(2)).resolve()
            if src.exists():
                doc.add_picture(str(src), width=Cm(18.8 if one_page else 16))
                last_p = doc.paragraphs[-1]
                last_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                if one_page:
                    tighten_p(last_p, 1.5, 1.5, None)
                    last_p.paragraph_format.line_spacing = 1.0
                elif img.group(1):
                    cap = doc.add_paragraph()
                    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    set_font(cap.add_run(img.group(1)), size=9, color="52514E")
            else:
                add_inline(doc.add_paragraph(), f"【圖檔遺失：{img.group(2)}】")
            i += 1
            continue

        m = re.match(r"^(#{1,6})\s+(.*)", stripped)
        if m:
            level = min(len(m.group(1)), 4)
            if one_page:
                p = doc.add_paragraph()
                if level == 1:
                    tighten_p(p, 0, 1.5, 13.5)
                    set_font(p.add_run(m.group(2).replace("**", "")), size=11.5, bold=True, color="0F172A")
                else:
                    tighten_p(p, 2.0, 1.0, 10.5)
                    set_font(p.add_run("■ " + m.group(2).replace("**", "")), size=8.5, bold=True, color="065F46")
            else:
                h = doc.add_heading(level=0 if level == 1 else level - 1)
                add_inline(h, m.group(2).replace("**", ""))
        elif re.match(r"^(-{3,}|\*{3,})$", stripped):
            if not one_page:
                doc.add_paragraph("─" * 30)
        elif re.match(r"^\s*[-*+]\s+", line):
            indent = (len(line) - len(line.lstrip())) // 2
            p = doc.add_paragraph(style="List Bullet 2" if indent else "List Bullet")
            if one_page:
                tighten_p(p, 0, 1, 9.0)
            add_inline(p, re.sub(r"^\s*[-*+]\s+", "", line), size=7.0 if one_page else None)
        elif re.match(r"^\s*\d+[.)]\s+", line):
            p = doc.add_paragraph(style="List Number")
            if one_page:
                tighten_p(p, 0, 1, 9.0)
            add_inline(p, re.sub(r"^\s*\d+[.)]\s+", "", line), size=7.0 if one_page else None)
        elif stripped.startswith(">"):
            if one_page:
                p = doc.add_paragraph()
                tighten_p(p, 1.0, 1.0, 8.2)
                add_inline(p, stripped.lstrip("> "), size=6.5)
                for run in p.runs:
                    if not run.font.color.rgb:
                        run.font.color.rgb = RGBColor.from_string("475569")
            else:
                p = doc.add_paragraph(style="Intense Quote")
                add_inline(p, stripped.lstrip("> "))
        elif stripped:
            p = doc.add_paragraph()
            if one_page:
                tighten_p(p, 0, 1.0, 9.0)
            add_inline(p, stripped, size=7.0 if one_page else None)
        i += 1

    out = md_path.with_suffix(".docx")
    doc.save(out)
    return out


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--all"]:
        targets = [p for p in ROOT.rglob("*.md")
                   if "tools" not in p.parts and ".git" not in p.parts]
    else:
        targets = [Path(a).resolve() for a in args]
    for t in targets:
        print("OK", convert(t))

