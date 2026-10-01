"""由 agricultural_labor_dispatch_pitch.md 產生同名 .pptx（.md 為原始檔，改內容請改 .md）。

用法：python make_ppt.py
需要 python-pptx（pip install python-pptx）。
"""
import re
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import MSO_AUTO_SIZE, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

HERE = Path(__file__).resolve().parent
SRC = HERE / 'agricultural_labor_dispatch_pitch.md'
OUT = SRC.with_suffix('.pptx')

# 版面與配色比照 generate_pitch_deck.py（加速器簡報）：16:9、微軟正黑體、品牌綠
FONT = 'Microsoft JhengHei'
BG = RGBColor(248, 250, 252)       # Slate 50
TEXT_MAIN = RGBColor(30, 41, 59)   # Slate 800
TEXT_SUB = RGBColor(100, 116, 139) # Slate 500
GREEN = RGBColor(16, 149, 93)
SLIDE_W, SLIDE_H = Inches(13.333), Inches(7.5)
LEVEL_PT = {0: 24, 1: 20, 2: 18, 3: 16}   # 內文各層字級；模板預設最大 32pt 會超出版面


def clean(text):
    """去掉 Markdown 粗體與行內程式碼標記。"""
    return re.sub(r'\*\*|`', '', text).strip()


def parse(md):
    """回傳 (標題, 副標, [{title, lead, bullets:[(level, text)]}])。"""
    title = subtitle = ''
    slides = []
    cur = None
    for raw in md.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip() == '---' or line.startswith('>'):
            continue
        if line.startswith('# '):
            title = clean(line[2:])
        elif line.startswith('## '):
            heading = clean(line[3:])
            heading = re.sub(r'^投影片\s*\d+[：:]\s*', '', heading)
            cur = {'title': heading, 'lead': '', 'bullets': []}
            slides.append(cur)
        elif cur is None:
            if not subtitle and line.startswith('**'):
                subtitle = clean(line)
        elif not cur['lead'] and not cur['bullets'] and line.startswith('**'):
            cur['lead'] = clean(line)
        else:
            m = re.match(r'^(\s*)(?:[*-]|\d+\.)\s+(.*)$', line)
            if m:
                level = 1 + len(m.group(1)) // 2
                cur['bullets'].append((level, clean(m.group(2))))
    return title, subtitle, slides


def set_font(rPr, face):
    """確保 rPr 依 schema 順序含 a:latin 與 a:ea（語系設繁中後 PowerPoint 改用 ea 字型，預設新細明體）。"""
    latin = rPr.find(qn('a:latin'))
    if latin is None:
        latin = rPr.makeelement(qn('a:latin'), {'typeface': face})
        later = [c for c in rPr if c.tag in {qn('a:ea'), qn('a:cs'), qn('a:sym'), qn('a:hlinkClick'),
                                              qn('a:hlinkMouseOver'), qn('a:rtl'), qn('a:extLst')}]
        later[0].addprevious(latin) if later else rPr.append(latin)
    if rPr.find(qn('a:ea')) is None:
        latin.addnext(rPr.makeelement(qn('a:ea'), {'typeface': latin.get('typeface')}))


def polish_cjk(prs):
    """全份套用中文排版：標點懸尾、東亞斷行、繁中語系與正黑體。"""
    for slide in prs.slides:
        for shape in slide.shapes:
            if not shape.has_text_frame:
                continue
            for para in shape.text_frame.paragraphs:
                pPr = para._p.get_or_add_pPr()
                pPr.set('hangingPunct', '1')
                pPr.set('eaLnBrk', '1')
                set_font(pPr.get_or_add_defRPr(), FONT)
                for run in para.runs:
                    rPr = run._r.get_or_add_rPr()
                    rPr.set('lang', 'zh-TW')
                    set_font(rPr, FONT)


def style(p, size, color, bold=False):
    p.font.size = Pt(size)
    p.font.color.rgb = color
    p.font.bold = bold
    p.font.name = FONT


def place(shape, left, top, width, height):
    shape.left, shape.top, shape.width, shape.height = Inches(left), Inches(top), Inches(width), Inches(height)
    tf = shape.text_frame
    tf.word_wrap = True
    tf.auto_size = MSO_AUTO_SIZE.NONE
    return tf


def new_slide(prs, layout):
    slide = prs.slides.add_slide(prs.slide_layouts[layout])
    slide.background.fill.solid()
    slide.background.fill.fore_color.rgb = BG
    return slide


def build():
    title, subtitle, slides = parse(SRC.read_text(encoding='utf-8'))
    prs = Presentation()
    prs.slide_width, prs.slide_height = SLIDE_W, SLIDE_H   # 預設為 4:3，改為 16:9

    cover = new_slide(prs, 0)
    tf = place(cover.shapes.title, 1.0, 2.2, 11.33, 1.6)
    tf.text = title
    style(tf.paragraphs[0], 36, TEXT_MAIN, bold=True)
    tf = place(cover.placeholders[1], 1.0, 4.0, 11.33, 1.0)
    tf.text = subtitle
    style(tf.paragraphs[0], 22, GREEN)
    for t in (cover.shapes.title, cover.placeholders[1]):
        t.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER

    for s in slides:
        slide = new_slide(prs, 1)
        tf = place(slide.shapes.title, 0.8, 0.4, 11.73, 0.9)
        tf.text = s['title']
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.LEFT
        style(p, 30, TEXT_MAIN, bold=True)

        tf = place(slide.placeholders[1], 0.8, 1.5, 11.73, 5.6)
        tf.text = s['lead']
        style(tf.paragraphs[0], LEVEL_PT[0], GREEN, bold=True)
        tf.paragraphs[0].space_after = Pt(6)
        for level, text in s['bullets']:
            p = tf.add_paragraph()
            p.text = text
            p.level = level
            style(p, LEVEL_PT.get(level, 14), TEXT_MAIN)
            p.space_after = Pt(4)

    polish_cjk(prs)
    prs.save(OUT)
    print(f'已產生 {OUT.name}：封面 + {len(slides)} 張投影片')


if __name__ == '__main__':
    build()
