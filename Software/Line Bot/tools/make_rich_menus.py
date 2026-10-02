# -*- coding: utf-8 -*-
"""產生兩個 LINE 官方帳號共 6 份圖文選單（規格 07_系統分析及文件/05_LINE官方帳號拆分規格.md 第捌章）。

一份定義同時輸出：
    rich_menu/<選單代碼>.json   LINE Rich Menu 結構（按鈕一律 postback，data 格式 a=動作&參數=值，規格 5.2）
    rich_menu/<選單代碼>.jpg    2500x1686 底圖（1MB 以內）

用法（於 Software/Line Bot 目錄）：
    python tools/make_rich_menus.py

需要 Pillow，字型使用 Windows 內建：微軟正黑體（中文）、Segoe UI（越南語、印尼語）、Segoe UI Emoji（圖示）。
改按鈕或文字只改本檔的 MENUS，重新執行後再用 RichMenuUploader 上傳。
"""
import json
import os
import re
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "rich_menu")
W, H = 2500, 1686
FONTS = "C:/Windows/Fonts/"
CJK = FONTS + "msjhbd.ttc"
CJK_REG = FONTS + "msjh.ttc"
LATIN = FONTS + "segoeuib.ttf"
LATIN_REG = FONTS + "segoeui.ttf"
EMOJI = FONTS + "seguiemj.ttf"

# 功能色（與 Flex 卡片標題列一致）
C = {
    "green": "#047857", "teal": "#0F766E", "blue": "#1D4ED8", "amber": "#B45309", "indigo": "#4338CA",
    "slate": "#334155", "cyan": "#0E7490", "gray": "#374151", "gold": "#A16207", "rose": "#9F1239",
}

# 每格：(圖示, 主標, 副標清單, postback data, 顏色)
MENUS = {
    "farmer_guest": {
        "chatBarText": "👨‍🌾 選單",
        "name": "智農派工（農場主）未綁定選單",
        "cols": 3,
        "cells": [
            ("⚡", "早鳥工時包", ["鎖定名額・優先排班"], "a=early_bird", "amber"),
            ("💰", "費率試算", ["時薪・日薪・包工"], "a=rates", "gold"),
            ("📅", "產曆查詢", ["全台作物採收期"], "a=calendar", "teal"),
            ("🚜", "機耕代噴預約", ["T50 植保・曳引機"], "a=machinery_book", "gray"),
            ("🔗", "帳號綁定", ["綁定後查看訂單"], "a=account", "cyan"),
            ("🌱", "服務導覽", ["功能說明"], "a=help", "slate"),
        ],
    },
    "farmer_main": {
        "chatBarText": "👨‍🌾 農場主選單",
        "name": "智農派工（農場主）已綁定選單",
        "cols": 4,
        "cells": [
            ("📋", "我的訂單", ["派工確認單"], "a=orders", "green"),
            ("🔄", "現有工班黑轉白", ["依身分合法轉換"], "a=legalize", "cyan"),
            ("⚡", "早鳥工時包", ["鎖定名額"], "a=early_bird", "amber"),
            ("🌧️", "天候避險", ["訂單受影響情形"], "a=weather", "blue"),
            ("🚜", "機耕代噴預約", ["T50 植保・曳引機"], "a=machinery_book", "gray"),
            ("💰", "費率與產曆", ["服務費率・全台產曆"], "a=rates_calendar", "gold"),
            ("👤", "我的帳號", ["綁定與解除"], "a=account", "teal"),
            ("🌱", "服務導覽", ["功能說明"], "a=help", "slate"),
        ],
    },
    "worker_guest": {
        "chatBarText": "🌐 Menu / 選單",
        "name": "智農派工（工作者）未綁定選單（三語）",
        "cols": 3,
        "cells": [
            ("🌐", "繁體中文", ["切換為中文介面"], "a=lang&v=zh", "indigo"),
            ("🌐", "Tiếng Việt", ["越南語"], "a=lang&v=vi", "indigo"),
            ("🌐", "Bahasa Indonesia", ["印尼語"], "a=lang&v=id", "indigo"),
            ("🔗", "帳號綁定", ["Liên kết tài khoản", "Hubungkan akun"], "a=account", "cyan"),
            ("📖", "使用說明", ["Hướng dẫn", "Panduan"], "a=help", "slate"),
            ("☎️", "聯絡合作社", ["Liên hệ hợp tác xã", "Hubungi koperasi"], "a=contact", "rose"),
        ],
    },
}

# 已綁定工作者選單：三種語言版型相同，只換文字；越語、印尼語版附中文小字方便帶班師傅辨識
WORKER_MAIN = [
    # (圖示, data, 顏色, {lang: (主標, 副標)})
    ("📋", "a=task", "green", {"zh": ("今日任務", "集合時間與地點"), "vi": ("Nhiệm vụ hôm nay", "今日任務"), "id": ("Tugas hari ini", "今日任務")}),
    ("⏱️", "a=clock", "teal", {"zh": ("上下班打卡", "到場與收工打卡"), "vi": ("Chấm công", "上下班打卡"), "id": ("Absen", "上下班打卡")}),
    ("🌧️", "a=weather", "blue", {"zh": ("天候與集合", "雨天改派・接駁"), "vi": ("Thời tiết & tập trung", "天候與集合"), "id": ("Cuaca & titik kumpul", "天候與集合")}),
    ("💰", "a=salary", "amber", {"zh": ("薪資明細", "薪資與津貼"), "vi": ("Lương", "薪資明細"), "id": ("Gaji", "薪資明細")}),
    ("📅", "a=attendance", "indigo", {"zh": ("我的出勤", "近 30 日紀錄"), "vi": ("Ngày công", "我的出勤"), "id": ("Kehadiran", "我的出勤")}),
    ("🌐", "a=lang_help", "slate", {"zh": ("語言・說明", "切換語言"), "vi": ("Ngôn ngữ & hướng dẫn", "語言・說明"), "id": ("Bahasa & panduan", "語言・說明")}),
]
WORKER_BAR = {"zh": "📋 工作選單", "vi": "📋 Công việc", "id": "📋 Menu kerja"}
for lang in ("zh", "vi", "id"):
    MENUS["worker_main_" + lang] = {
        "chatBarText": WORKER_BAR[lang],
        "name": "智農派工（工作者）已綁定選單（" + lang + "）",
        "cols": 3,
        "cells": [(icon, labels[lang][0], [labels[lang][1]], data, color) for icon, data, color, labels in WORKER_MAIN],
    }

CJK_RE = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")


def font_for(text, size, bold=True):
    if CJK_RE.search(text):
        return ImageFont.truetype(CJK if bold else CJK_REG, size)
    return ImageFont.truetype(LATIN if bold else LATIN_REG, size)


def fit(draw, text, max_w, start, bold=True, min_size=40):
    size = start
    while size > min_size:
        f = font_for(text, size, bold)
        if draw.textlength(text, font=f) <= max_w:
            return f
        size -= 4
    return font_for(text, min_size, bold)


def shade(hex_color, factor):
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return (int(r * factor), int(g * factor), int(b * factor))


def bounds(cols, rows=2):
    xs = [round(W * i / cols) for i in range(cols + 1)]
    ys = [round(H * i / rows) for i in range(rows + 1)]
    for r in range(rows):
        for c in range(cols):
            yield xs[c], ys[r], xs[c + 1] - xs[c], ys[r + 1] - ys[r]


def render(key, menu):
    im = Image.new("RGB", (W, H), "#0B1220")
    d = ImageDraw.Draw(im)
    emoji_font = ImageFont.truetype(EMOJI, 109)  # Segoe UI Emoji 彩色點陣在 109px 最清楚
    areas = []
    for (x, y, w, h), (icon, title, subs, data, color) in zip(bounds(menu["cols"]), menu["cells"]):
        m = 14
        base = C[color]
        d.rounded_rectangle([x + m, y + m, x + w - m, y + h - m], radius=48, fill=shade(base, 1.0))
        d.rounded_rectangle([x + m, y + h // 2 + 40, x + w - m, y + h - m], radius=48, fill=shade(base, 0.82))
        d.rectangle([x + m, y + h // 2 + 40, x + w - m, y + h // 2 + 90], fill=shade(base, 0.82))

        # 圖示：放大貼上（去掉 VS16 以免多出空白）
        glyph = icon.replace("\ufe0f", "")
        tmp = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
        ImageDraw.Draw(tmp).text((10, 10), glyph, font=emoji_font, embedded_color=True)
        bbox = tmp.getbbox() or (0, 0, 160, 160)
        tmp = tmp.crop(bbox)
        scale = 230 / max(tmp.width, tmp.height)
        tmp = tmp.resize((int(tmp.width * scale), int(tmp.height * scale)), Image.LANCZOS)
        im.paste(tmp, (x + (w - tmp.width) // 2, y + 120), tmp)

        cx = x + w // 2
        tf = fit(d, title, w - 2 * m - 70, 104 if menu["cols"] == 3 else 92)
        d.text((cx, y + 470), title, font=tf, fill="white", anchor="mm")
        sy = y + 590
        for s in subs:
            sf = fit(d, s, w - 2 * m - 70, 60, bold=False, min_size=34)
            d.text((cx, sy), s, font=sf, fill=(226, 232, 240), anchor="mm")
            sy += 78

        areas.append({
            "bounds": {"x": x, "y": y, "width": w, "height": h},
            "action": {"type": "postback", "label": title[:20], "data": data, "displayText": title},
        })

    jpg = os.path.join(OUT, key + ".jpg")
    im.save(jpg, "JPEG", quality=88, optimize=True)
    size = os.path.getsize(jpg)
    assert size <= 1024 * 1024, f"{key}.jpg 超過 1MB：{size}"

    spec = {
        "size": {"width": W, "height": H},
        "selected": True,
        "name": menu["name"],
        "chatBarText": menu["chatBarText"],
        "areas": areas,
    }
    assert len(menu["chatBarText"]) <= 14, f"{key} chatBarText 超過 14 字"
    with open(os.path.join(OUT, key + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(spec, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"✅ {key}: {len(areas)} 格，{size // 1024} KB")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for k, v in MENUS.items():
        render(k, v)
