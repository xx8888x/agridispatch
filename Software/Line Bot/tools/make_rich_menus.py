# -*- coding: utf-8 -*-
"""產生兩個 LINE 官方帳號共 6 份圖文選單（規格 07_系統分析及文件/05_LINE官方帳號拆分規格.md 第捌章）。

一份定義同時輸出：
    rich_menu/<選單代碼>.json   LINE Rich Menu 結構（按鈕一律 postback，data 格式 a=動作&參數=值，規格 5.2）
    rich_menu/<選單代碼>.jpg    2500x1686 底圖（1MB 以內）

用法（於 Software/Line Bot 目錄）：
    python tools/make_rich_menus.py

需要 Pillow，字型使用 Windows 內建：微軟正黑體（中文）、Segoe UI（越南語、印尼語）。
圖示用 lucide 線條圖示（tools/lucide_icons.py，與前端設計系統同一套），不用 emoji；
顏色依 04_UI設計系統規範：綠＝主要動作、灰＝資訊與設定、琥珀＝錢、藍＝天候、玫瑰＝求助。
改按鈕或文字只改本檔的 MENUS，重新執行後再用 RichMenuUploader 上傳。
"""
import json
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lucide_icons import ICONS  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "rich_menu")
W, H = 2500, 1686
FONTS = "C:/Windows/Fonts/"
CJK = FONTS + "msjhbd.ttc"
CJK_REG = FONTS + "msjh.ttc"
LATIN = FONTS + "segoeuib.ttf"
LATIN_REG = FONTS + "segoeui.ttf"

# 功能色（與 Flex 卡片標題列一致）
C = {
    "green": "#047857",   # 主要動作
    "slate": "#334155",   # 資訊、設定
    "amber": "#B45309",   # 錢：工時包、費率、薪資
    "blue": "#1D4ED8",    # 天候
    "rose": "#9F1239",    # 求助
}

# 功能開關：工班合法化（黑轉白）。False＝暫時下架（與 Line Bot 的 feature.legalize、前端 features.legalize 一致），
# 農場主已綁定選單改把「費率與產曆」拆成「費率試算」「產曆查詢」兩格補位；改 True 後重新執行並上傳即恢復。
FEATURE_LEGALIZE = False

FARMER_MAIN_CELLS = [
    ("clipboard-list", "我的訂單", ["派工確認單"], "a=orders", "green"),
    ("refresh-cw", "現有工班黑轉白", ["依身分合法轉換"], "a=legalize", "green"),
    ("zap", "早鳥工時包", ["鎖定名額"], "a=early_bird", "amber"),
    ("cloud-rain", "天候避險", ["訂單受影響情形"], "a=weather", "blue"),
    ("tractor", "機耕代噴預約", ["T50 植保・曳引機"], "a=machinery_book", "slate"),
    ("coins", "費率與產曆", ["服務費率・全台產曆"], "a=rates_calendar", "slate"),
    ("user", "我的帳號", ["綁定與解除"], "a=account", "slate"),
    ("sprout", "服務導覽", ["功能說明"], "a=help", "slate"),
] if FEATURE_LEGALIZE else [
    ("clipboard-list", "我的訂單", ["派工確認單"], "a=orders", "green"),
    ("zap", "早鳥工時包", ["鎖定名額"], "a=early_bird", "amber"),
    ("cloud-rain", "天候避險", ["訂單受影響情形"], "a=weather", "blue"),
    ("tractor", "機耕代噴預約", ["T50 植保・曳引機"], "a=machinery_book", "slate"),
    ("coins", "費率試算", ["時薪・日薪・包工"], "a=rates", "slate"),
    ("calendar-days", "產曆查詢", ["全台作物採收期"], "a=calendar", "green"),
    ("user", "我的帳號", ["綁定與解除"], "a=account", "slate"),
    ("sprout", "服務導覽", ["功能說明"], "a=help", "slate"),
]

# 功能開關：外籍移工（外勞）服務。False＝暫時下架（與 Line Bot 的 feature.foreign-workers、前端 features.foreignWorkers 一致），
# 先以本國勞工為主：工作者未綁定選單拿掉三語切換、改為中文功能格；已綁定中文選單「語言・說明」改「使用說明」。
# 越語、印尼語選單檔照舊產生（下架期間不會連結給任何人）。改 True 後重新執行並上傳即恢復。
FEATURE_FOREIGN_WORKERS = False

WORKER_GUEST_CELLS = [
    ("languages", "繁體中文", ["切換為中文介面"], "a=lang&v=zh", "green"),
    ("languages", "Tiếng Việt", ["越南語"], "a=lang&v=vi", "green"),
    ("languages", "Bahasa Indonesia", ["印尼語"], "a=lang&v=id", "green"),
    ("link", "帳號綁定", ["Liên kết tài khoản", "Hubungkan akun"], "a=account", "slate"),
    ("book-open", "使用說明", ["Hướng dẫn", "Panduan"], "a=help", "slate"),
    ("phone-call", "聯絡合作社", ["Liên hệ hợp tác xã", "Hubungi koperasi"], "a=contact", "rose"),
] if FEATURE_FOREIGN_WORKERS else [
    ("clipboard-list", "今日任務", ["綁定後查看"], "a=task", "green"),
    ("clock", "上下班打卡", ["綁定後使用"], "a=clock", "green"),
    ("wallet", "薪資明細", ["綁定後查看"], "a=salary", "amber"),
    ("link", "帳號綁定", ["本國籍工作者"], "a=account", "slate"),
    ("book-open", "使用說明", ["功能說明"], "a=help", "slate"),
    ("phone-call", "聯絡合作社", ["服務專線・1955"], "a=contact", "rose"),
]

# 每格：(lucide 圖示, 主標, 副標清單, postback data, 顏色)
MENUS = {
    "farmer_guest": {
        "chatBarText": "選單",
        "name": "智農派工（農場主）未綁定選單",
        "cols": 3,
        "cells": [
            ("zap", "早鳥工時包", ["鎖定名額・優先排班"], "a=early_bird", "amber"),
            ("coins", "費率試算", ["時薪・日薪・包工"], "a=rates", "slate"),
            ("calendar-days", "產曆查詢", ["全台作物採收期"], "a=calendar", "green"),
            ("tractor", "機耕代噴預約", ["T50 植保・曳引機"], "a=machinery_book", "green"),
            ("link", "帳號綁定", ["綁定後查看訂單"], "a=account", "slate"),
            ("sprout", "服務導覽", ["功能說明"], "a=help", "slate"),
        ],
    },
    "farmer_main": {
        "chatBarText": "農場主選單",
        "name": "智農派工（農場主）已綁定選單",
        "cols": 4,
        "cells": FARMER_MAIN_CELLS,
    },
    "worker_guest": {
        "chatBarText": "Menu / 選單" if FEATURE_FOREIGN_WORKERS else "選單",
        "name": "智農派工（工作者）未綁定選單（三語）" if FEATURE_FOREIGN_WORKERS else "智農派工（工作者）未綁定選單",
        "cols": 3,
        "cells": WORKER_GUEST_CELLS,
    },
}

# 已綁定工作者選單：三種語言版型相同，只換文字；越語、印尼語版附中文小字方便帶班師傅辨識
WORKER_MAIN = [
    # (圖示, data, 顏色, {lang: (主標, 副標)})
    ("clipboard-list", "a=task", "green", {"zh": ("今日任務", "集合時間與地點"), "vi": ("Nhiệm vụ hôm nay", "今日任務"), "id": ("Tugas hari ini", "今日任務")}),
    ("clock", "a=clock", "green", {"zh": ("上下班打卡", "到場與收工打卡"), "vi": ("Chấm công", "上下班打卡"), "id": ("Absen", "上下班打卡")}),
    ("cloud-rain", "a=weather", "blue", {"zh": ("天候與集合", "雨天改派・接駁"), "vi": ("Thời tiết & tập trung", "天候與集合"), "id": ("Cuaca & titik kumpul", "天候與集合")}),
    ("wallet", "a=salary", "amber", {"zh": ("薪資明細", "薪資與津貼"), "vi": ("Lương", "薪資明細"), "id": ("Gaji", "薪資明細")}),
    ("calendar-days", "a=attendance", "slate", {"zh": ("我的出勤", "近 30 日紀錄"), "vi": ("Ngày công", "我的出勤"), "id": ("Kehadiran", "我的出勤")}),
    ("languages", "a=lang_help", "slate", {"zh": ("語言・說明", "切換語言"), "vi": ("Ngôn ngữ & hướng dẫn", "語言・說明"), "id": ("Bahasa & panduan", "語言・說明")}),
]
WORKER_BAR = {"zh": "工作選單", "vi": "Công việc", "id": "Menu kerja"}
for lang in ("zh", "vi", "id"):
    MENUS["worker_main_" + lang] = {
        "chatBarText": WORKER_BAR[lang],
        "name": "智農派工（工作者）已綁定選單（" + lang + "）",
        "cols": 3,
        "cells": [(icon, labels[lang][0], [labels[lang][1]], data, color) for icon, data, color, labels in WORKER_MAIN],
    }
if not FEATURE_FOREIGN_WORKERS:
    # 下架期間中文選單不提供切換語言：最後一格改為使用說明
    MENUS["worker_main_zh"]["cells"][-1] = ("book-open", "使用說明", ["功能說明"], "a=help", "slate")

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


def bounds(cols, rows=2):
    xs = [round(W * i / cols) for i in range(cols + 1)]
    ys = [round(H * i / rows) for i in range(rows + 1)]
    for r in range(rows):
        for c in range(cols):
            yield xs[c], ys[r], xs[c + 1] - xs[c], ys[r + 1] - ys[r]


# ---------------------------------------------------------------------------
# lucide 圖示：把 24x24 的 SVG 線條（path／circle／rect／polygon）攤成折線後用 Pillow 畫出來
# ---------------------------------------------------------------------------
_NUM = re.compile(r"[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?")
_WS = re.compile(r"[\s,]*")


class _Scanner:
    """SVG path 字串掃描器：弧線的兩個旗標是單一字元、可以不加分隔（例 "0 00-2.474"），所以不能先整串切數字"""

    def __init__(self, d):
        self.d, self.pos = d, 0

    def skip(self):
        self.pos = _WS.match(self.d, self.pos).end()

    def peek_cmd(self):
        self.skip()
        return self.pos < len(self.d) and self.d[self.pos].isalpha()

    def cmd(self):
        c = self.d[self.pos]
        self.pos += 1
        return c

    def num(self):
        self.skip()
        m = _NUM.match(self.d, self.pos)
        if not m:
            raise ValueError(f"無法解析 path：{self.d[self.pos:self.pos + 20]!r}")
        self.pos = m.end()
        return float(m.group())

    def flag(self):
        self.skip()
        c = self.d[self.pos]
        if c not in "01":
            raise ValueError(f"弧線旗標應為 0/1：{self.d[self.pos:self.pos + 20]!r}")
        self.pos += 1
        return c == "1"

    def more(self):
        self.skip()
        return self.pos < len(self.d)


def _cubic(p0, p1, p2, p3, n=14):
    pts = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        pts.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return pts


def _arc(p0, rx, ry, phi, large, sweep, p1):
    """SVG 橢圓弧 → 折線（SVG 規格 F.6.5 端點轉圓心）"""
    if rx == 0 or ry == 0 or p0 == p1:
        return [p1]
    phi = math.radians(phi)
    cphi, sphi = math.cos(phi), math.sin(phi)
    dx, dy = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1 = cphi * dx + sphi * dy
    y1 = -sphi * dx + cphi * dy
    rx, ry = abs(rx), abs(ry)
    lam = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
    if lam > 1:
        rx *= math.sqrt(lam)
        ry *= math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    den = rx * rx * y1 * y1 + ry * ry * x1 * x1
    coef = math.sqrt(max(num / den, 0)) * (-1 if large == sweep else 1)
    cx1 = coef * rx * y1 / ry
    cy1 = -coef * ry * x1 / rx
    cx = cphi * cx1 - sphi * cy1 + (p0[0] + p1[0]) / 2
    cy = sphi * cx1 + cphi * cy1 + (p0[1] + p1[1]) / 2

    def ang(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    t1 = ang(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry)
    dt = ang((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(4, int(abs(dt) / (math.pi / 16)))
    pts = []
    for i in range(1, n + 1):
        t = t1 + dt * i / n
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        pts.append((cphi * ex - sphi * ey + cx, sphi * ex + cphi * ey + cy))
    return pts


def _path_polylines(d):
    """path 的 d 屬性 → 多條折線（M/L/H/V/C/S/Q/T/A/Z 與相對座標、隱含重複）"""
    s = _Scanner(d)
    lines, cur = [], None
    pos = start = (0.0, 0.0)
    prev_ctrl = None
    cmd = None
    while s.more():
        if s.peek_cmd():
            cmd = s.cmd()
        if cmd is None:
            raise ValueError("path 未以指令開頭：" + d[:20])
        rel = cmd.islower()
        c = cmd.upper()

        def pt(x, y):
            return (pos[0] + x, pos[1] + y) if rel else (x, y)

        if c == "M":
            pos = start = pt(s.num(), s.num())
            cur = [pos]
            lines.append(cur)
            cmd = "l" if rel else "L"   # M 之後的隱含座標視為 L
            prev_ctrl = None
        elif c == "Z":
            if cur and cur[-1] != start:
                cur.append(start)
            pos = start
            cur = None
            prev_ctrl = None
            cmd = None
        elif c == "L":
            pos = pt(s.num(), s.num())
            cur.append(pos)
            prev_ctrl = None
        elif c == "H":
            x = s.num()
            pos = (pos[0] + x if rel else x, pos[1])
            cur.append(pos)
            prev_ctrl = None
        elif c == "V":
            y = s.num()
            pos = (pos[0], pos[1] + y if rel else y)
            cur.append(pos)
            prev_ctrl = None
        elif c in "CS":
            if c == "C":
                p1 = pt(s.num(), s.num())
            else:
                p1 = (2 * pos[0] - prev_ctrl[0], 2 * pos[1] - prev_ctrl[1]) if prev_ctrl else pos
            p2 = pt(s.num(), s.num())
            p3 = pt(s.num(), s.num())
            cur.extend(_cubic(pos, p1, p2, p3))
            prev_ctrl, pos = p2, p3
        elif c in "QT":
            if c == "Q":
                q1 = pt(s.num(), s.num())
            else:
                q1 = (2 * pos[0] - prev_ctrl[0], 2 * pos[1] - prev_ctrl[1]) if prev_ctrl else pos
            p3 = pt(s.num(), s.num())
            p1 = (pos[0] + 2 / 3 * (q1[0] - pos[0]), pos[1] + 2 / 3 * (q1[1] - pos[1]))
            p2 = (p3[0] + 2 / 3 * (q1[0] - p3[0]), p3[1] + 2 / 3 * (q1[1] - p3[1]))
            cur.extend(_cubic(pos, p1, p2, p3))
            prev_ctrl, pos = q1, p3
        elif c == "A":
            rx, ry, phi = s.num(), s.num(), s.num()
            large, sweep = s.flag(), s.flag()
            p1 = pt(s.num(), s.num())
            cur.extend(_arc(pos, rx, ry, phi, large, sweep, p1))
            pos = p1
            prev_ctrl = None
        else:
            raise ValueError("未支援的 path 指令 " + cmd)
    return lines


def _shape_polylines(tag, a):
    f = lambda k, d=0.0: float(a.get(k, d))  # noqa: E731
    if tag == "path":
        return _path_polylines(a["d"])
    if tag == "circle":
        cx, cy, r = f("cx"), f("cy"), f("r")
        return [[(cx + r * math.cos(t * math.pi / 24), cy + r * math.sin(t * math.pi / 24)) for t in range(49)]]
    if tag == "ellipse":
        cx, cy, rx, ry = f("cx"), f("cy"), f("rx"), f("ry")
        return [[(cx + rx * math.cos(t * math.pi / 24), cy + ry * math.sin(t * math.pi / 24)) for t in range(49)]]
    if tag == "rect":
        x, y, w, h, rx = f("x"), f("y"), f("width"), f("height"), f("rx", f("ry"))
        if rx <= 0:
            return [[(x, y), (x + w, y), (x + w, y + h), (x, y + h), (x, y)]]
        d = (f"M{x + rx} {y}h{w - 2 * rx}a{rx} {rx} 0 0 1 {rx} {rx}v{h - 2 * rx}a{rx} {rx} 0 0 1 -{rx} {rx}"
             f"h-{w - 2 * rx}a{rx} {rx} 0 0 1 -{rx} -{rx}v-{h - 2 * rx}a{rx} {rx} 0 0 1 {rx} -{rx}z")
        return _path_polylines(d)
    if tag == "line":
        return [[(f("x1"), f("y1")), (f("x2"), f("y2"))]]
    if tag in ("polyline", "polygon"):
        nums = [float(v) for v in _NUM.findall(a["points"])]
        pts = list(zip(nums[0::2], nums[1::2]))
        if tag == "polygon":
            pts.append(pts[0])
        return [pts]
    raise ValueError("未支援的 SVG 元素 " + tag)


def icon_image(name, size, color=(255, 255, 255), stroke=2.0, ss=4):
    """把 lucide 圖示畫成 size×size 的 RGBA 圖（超取樣後縮小，邊緣平滑；圓頭圓角線條）"""
    if name not in ICONS:
        raise KeyError(f"tools/lucide_icons.py 沒有圖示 {name}")
    big = size * ss
    scale = big / 24
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = max(1, round(stroke * scale))
    r = w / 2
    for tag, attrs in ICONS[name]:
        for pts in _shape_polylines(tag, attrs):
            pts = [(x * scale, y * scale) for x, y in pts]
            if len(pts) >= 2:
                d.line(pts, fill=color, width=w, joint="curve")
            for x, y in (pts[0], pts[-1]):  # 圓頭；長度 0 的 "h.01" 會變成一個點
                d.ellipse([x - r, y - r, x + r, y + r], fill=color)
    return im.resize((size, size), Image.LANCZOS)


def render(key, menu):
    im = Image.new("RGB", (W, H), "#0B1220")
    d = ImageDraw.Draw(im)
    areas = []
    for (x, y, w, h), (icon, title, subs, data, color) in zip(bounds(menu["cols"]), menu["cells"]):
        m = 14
        d.rounded_rectangle([x + m, y + m, x + w - m, y + h - m], radius=48, fill=C[color])

        ic = icon_image(icon, 230)
        im.paste(ic, (x + (w - ic.width) // 2, y + 125), ic)

        cx = x + w // 2
        tf = fit(d, title, w - 2 * m - 70, 104 if menu["cols"] == 3 else 92)
        d.text((cx, y + 480), title, font=tf, fill="white", anchor="mm")
        sy = y + 600
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
    print(f"OK {key}: {len(areas)} 格，{size // 1024} KB")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for k, v in MENUS.items():
        render(k, v)
