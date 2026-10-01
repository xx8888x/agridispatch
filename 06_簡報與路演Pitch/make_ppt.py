"""由 agricultural_labor_dispatch_pitch.md 產生同名 .pptx（.md 為原始檔，改內容請改 .md）。

用法：python make_ppt.py
需要 python-pptx（pip install python-pptx）。
"""
import re
from pathlib import Path

from pptx import Presentation

HERE = Path(__file__).resolve().parent
SRC = HERE / 'agricultural_labor_dispatch_pitch.md'
OUT = SRC.with_suffix('.pptx')


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


def build():
    title, subtitle, slides = parse(SRC.read_text(encoding='utf-8'))
    prs = Presentation()

    cover = prs.slides.add_slide(prs.slide_layouts[0])
    cover.shapes.title.text = title
    cover.placeholders[1].text = subtitle

    for s in slides:
        slide = prs.slides.add_slide(prs.slide_layouts[1])
        slide.shapes.title.text = s['title']
        tf = slide.placeholders[1].text_frame
        tf.text = s['lead']
        for level, text in s['bullets']:
            p = tf.add_paragraph()
            p.text = text
            p.level = level

    prs.save(OUT)
    print(f'已產生 {OUT.name}：封面 + {len(slides)} 張投影片')


if __name__ == '__main__':
    build()
