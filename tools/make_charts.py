# -*- coding: utf-8 -*-
"""產生財務文件用圖表（SVG → PNG，以 Edge headless 轉檔，不需額外套件）。

數字來源：02_財務模型與政府補助/03_財務計畫報表2_…（F2 v2.1 財務定版）。
F2 數字變動時，更新下方 DATA 後重新執行：
    python tools/make_charts.py
輸出：02_財務模型與政府補助/charts/*.svg、*.png
配色：dataviz 參考色盤淺色模式前 3 槽（已以 validate_palette 驗證）；
aqua 對白底對比 < 3:1，故每張圖都有數值標籤，文件中亦保留數據表。
"""
import html
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "02_財務模型與政府補助" / "charts"
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# ── 色彩與版面（角色 token） ─────────────────────────────────────────
SURFACE = "#ffffff"
TEXT_1 = "#0b0b0b"
TEXT_2 = "#52514e"
TEXT_3 = "#7a7974"
GRID = "#e6e5e0"
AXIS = "#b9b8b2"
S1, S2, S3 = "#2a78d6", "#eb6834", "#1baf7a"   # 類別色 1–3（固定順序）
FONT = "Microsoft JhengHei, PingFang TC, Noto Sans TC, sans-serif"
W, H = 960, 520
PAD_L, PAD_R, PAD_T, PAD_B = 88, 32, 128, 64

SOURCE = "資料來源：F2 財務計畫報表 2（v2.1 財務定版），基本情境不含政府補助"


def esc(s):
    return html.escape(str(s))


def wan(v, digits=1):
    """元 → 萬元字串（四捨五入，與文件表格一致）。"""
    from decimal import Decimal, ROUND_HALF_UP
    x = (Decimal(str(v)) / 10000).quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP)
    s = f"{x:,.{digits}f}"
    return s.rstrip("0").rstrip(".") if "." in s else s


def nice_ticks(lo, hi, n=5):
    # 預留 8% 空間給資料端的數值標籤，避免與座標軸文字重疊
    pad = (hi - lo) * 0.08
    lo = lo - pad if lo < 0 else lo
    hi = hi + pad if hi > 0 else hi
    span = hi - lo
    raw = span / n
    mag = 10 ** len(str(int(abs(raw)))) / 10 if raw >= 1 else 1
    for m in (1, 2, 2.5, 5, 10):
        step = m * mag
        if span / step <= n:
            break
    import math
    start = math.floor(lo / step) * step
    end = math.ceil(hi / step) * step
    ticks, t = [], start
    while t <= end + 1e-9:
        ticks.append(round(t, 6))
        t += step
    return ticks


def header(title, subtitle, legend):
    out = [f'<text x="{PAD_L - 56}" y="40" font-size="22" font-weight="700" fill="{TEXT_1}">{esc(title)}</text>',
           f'<text x="{PAD_L - 56}" y="68" font-size="14" fill="{TEXT_2}">{esc(subtitle)}</text>']
    x = PAD_L - 56
    for name, color in legend:
        out.append(f'<rect x="{x}" y="88" width="14" height="14" rx="3" fill="{color}"/>')
        out.append(f'<text x="{x + 20}" y="100" font-size="14" fill="{TEXT_1}">{esc(name)}</text>')
        x += 32 + 15 * len(name)
    return out


def footer(note=SOURCE):
    return [f'<text x="{PAD_L - 56}" y="{H - 16}" font-size="12" fill="{TEXT_3}">{esc(note)}</text>']


def bar_path(x, y0, y1, w, r=4):
    """由基線 y0 延伸到 y1 的長條，只有資料端（遠離基線那端）為 4px 圓角。"""
    top, bot = min(y0, y1), max(y0, y1)
    h = bot - top
    r = min(r, h / 2, w / 2)
    if y1 < y0:   # 正值：圓角在上
        return (f"M{x},{bot} L{x},{top + r} Q{x},{top} {x + r},{top} L{x + w - r},{top} "
                f"Q{x + w},{top} {x + w},{top + r} L{x + w},{bot} Z")
    return (f"M{x},{top} L{x + w},{top} L{x + w},{bot - r} Q{x + w},{bot} {x + w - r},{bot} "
            f"L{x + r},{bot} Q{x},{bot} {x},{bot - r} Z")


def y_axis(ticks, ymap, fmt):
    out = []
    for t in ticks:
        y = ymap(t)
        color = AXIS if t == 0 else GRID
        width = 1.5 if t == 0 else 1
        out.append(f'<line x1="{PAD_L}" x2="{W - PAD_R}" y1="{y:.1f}" y2="{y:.1f}" stroke="{color}" stroke-width="{width}"/>')
        out.append(f'<text x="{PAD_L - 10}" y="{y + 5:.1f}" font-size="13" fill="{TEXT_2}" text-anchor="end">{esc(fmt(t))}</text>')
    return out


def svg_wrap(parts):
    body = "\n  ".join(parts)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
            f'font-family="{FONT}">\n  <rect width="{W}" height="{H}" fill="{SURFACE}"/>\n  {body}\n</svg>\n')


def grouped_bars(categories, series, title, subtitle, unit="萬元"):
    vals = [v for s in series for v in s["values"]]
    lo, hi = min(0, min(vals)), max(0, max(vals))
    ticks = nice_ticks(lo, hi)
    lo, hi = ticks[0], ticks[-1]
    plot_h = H - PAD_T - PAD_B
    ymap = lambda v: PAD_T + (hi - v) / (hi - lo) * plot_h
    parts = header(title, subtitle, [(s["name"], s["color"]) for s in series])
    parts += y_axis(ticks, ymap, lambda t: wan(t, 0))
    group_w = (W - PAD_L - PAD_R) / len(categories)
    n = len(series)
    bw = min(64, (group_w * 0.62 - 2 * (n - 1)) / n)
    zero = ymap(0)
    for i, cat in enumerate(categories):
        gx = PAD_L + group_w * i + (group_w - (bw * n + 2 * (n - 1))) / 2
        for j, s in enumerate(series):
            v = s["values"][i]
            x = gx + j * (bw + 2)
            y = ymap(v)
            if abs(y - zero) >= 0.5:
                parts.append(f'<path d="{bar_path(x, zero, y, bw)}" fill="{s["color"]}"><title>{esc(s["name"])} {esc(cat)}：{wan(v)} 萬元</title></path>')
            ly = y - 8 if v >= 0 else y + 18
            parts.append(f'<text x="{x + bw / 2:.1f}" y="{ly:.1f}" font-size="13" fill="{TEXT_1}" text-anchor="middle">{wan(v)}</text>')
        parts.append(f'<text x="{PAD_L + group_w * i + group_w / 2:.1f}" y="{H - PAD_B + 26}" font-size="15" fill="{TEXT_1}" text-anchor="middle">{esc(cat)}</text>')
    return svg_wrap(parts + footer())


def stacked_bars(categories, series, title, subtitle, unit="萬元", total_label="合計", note=SOURCE):
    totals = [sum(s["values"][i] for s in series) for i in range(len(categories))]
    ticks = nice_ticks(0, max(totals))
    hi = ticks[-1]
    plot_h = H - PAD_T - PAD_B
    ymap = lambda v: PAD_T + (hi - v) / hi * plot_h
    parts = header(title, subtitle, [(s["name"], s["color"]) for s in series])
    parts += y_axis(ticks, ymap, lambda t: wan(t, 0))
    group_w = (W - PAD_L - PAD_R) / len(categories)
    bw = min(96, group_w * 0.5)
    for i, cat in enumerate(categories):
        x = PAD_L + group_w * i + (group_w - bw) / 2
        acc = 0
        visible = [j for j, s in enumerate(series) if s["values"][i] > 0]
        for j, s in enumerate(series):
            v = s["values"][i]
            if v <= 0:
                continue
            y0, y1 = ymap(acc), ymap(acc + v)
            acc += v
            is_top = j == visible[-1]
            gap = 0 if j == visible[0] else 2          # 片段之間 2px 底色間隙
            y0g = y0 - gap
            if y0g - y1 < 0.5:
                continue
            if is_top:
                d = bar_path(x, y0g, y1, bw)
            else:
                d = f"M{x},{y0g:.1f} L{x + bw},{y0g:.1f} L{x + bw},{y1:.1f} L{x},{y1:.1f} Z"
            parts.append(f'<path d="{d}" fill="{s["color"]}"><title>{esc(s["name"])} {esc(cat)}：{wan(v)} 萬元</title></path>')
        parts.append(f'<text x="{x + bw / 2:.1f}" y="{ymap(totals[i]) - 10:.1f}" font-size="14" font-weight="700" fill="{TEXT_1}" text-anchor="middle">{total_label} {wan(totals[i])}</text>')
        parts.append(f'<text x="{PAD_L + group_w * i + group_w / 2:.1f}" y="{H - PAD_B + 26}" font-size="15" fill="{TEXT_1}" text-anchor="middle">{esc(cat)}</text>')
    return svg_wrap(parts + footer(note))


def hbars(labels, values, title, subtitle, color=S1, highlight=None, note=SOURCE):
    left = 300
    lo = min(0, min(values))
    hi = max(0, max(values))
    ticks = nice_ticks(lo, hi)
    lo, hi = ticks[0], ticks[-1]
    top, bottom = 104, H - 70
    xmap = lambda v: left + (v - lo) / (hi - lo) * (W - left - PAD_R - 40)
    parts = header(title, subtitle, [])
    for t in ticks:
        x = xmap(t)
        color_l = AXIS if t == 0 else GRID
        parts.append(f'<line x1="{x:.1f}" x2="{x:.1f}" y1="{top}" y2="{bottom}" stroke="{color_l}" stroke-width="{1.5 if t == 0 else 1}"/>')
        parts.append(f'<text x="{x:.1f}" y="{bottom + 20}" font-size="13" fill="{TEXT_2}" text-anchor="middle">{wan(t, 0)}</text>')
    parts.append(f'<text x="{W - PAD_R}" y="{bottom + 20}" font-size="12" fill="{TEXT_3}" text-anchor="end">萬元</text>')
    row_h = (bottom - top) / len(labels)
    bh = min(30, row_h * 0.62)
    zero = xmap(0)
    for i, (lab, v) in enumerate(zip(labels, values)):
        y = top + row_h * i + (row_h - bh) / 2
        x1 = xmap(v)
        c = TEXT_3 if highlight is not None and i == highlight else color
        # 水平長條：資料端圓角
        xa, xb = min(zero, x1), max(zero, x1)
        r = min(4, bh / 2, (xb - xa) / 2)
        if v < 0:
            d = (f"M{xb},{y} L{xa + r},{y} Q{xa},{y} {xa},{y + r} L{xa},{y + bh - r} "
                 f"Q{xa},{y + bh} {xa + r},{y + bh} L{xb},{y + bh} Z")
        else:
            d = (f"M{xa},{y} L{xb - r},{y} Q{xb},{y} {xb},{y + r} L{xb},{y + bh - r} "
                 f"Q{xb},{y + bh} {xb - r},{y + bh} L{xa},{y + bh} Z")
        parts.append(f'<path d="{d}" fill="{c}"><title>{esc(lab)}：{wan(v)} 萬元</title></path>')
        parts.append(f'<text x="{left - 12}" y="{y + bh / 2 + 5:.1f}" font-size="14" fill="{TEXT_1}" text-anchor="end">{esc(lab)}</text>')
        lx = xa - 8 if v < 0 else xb + 8
        anchor = "end" if v < 0 else "start"
        parts.append(f'<text x="{lx:.1f}" y="{y + bh / 2 + 5:.1f}" font-size="13" fill="{TEXT_1}" text-anchor="{anchor}">{wan(v)}</text>')
    return svg_wrap(parts + footer(note))


def line_chart(categories, values, title, subtitle, color=S1, note=SOURCE):
    ticks = nice_ticks(0, max(values))
    hi = ticks[-1]
    plot_h = H - PAD_T - PAD_B
    ymap = lambda v: PAD_T + (hi - v) / hi * plot_h
    parts = header(title, subtitle, [])
    parts += y_axis(ticks, ymap, lambda t: wan(t, 0))
    step = (W - PAD_L - PAD_R - 80) / (len(categories) - 1)
    pts = [(PAD_L + 40 + step * i, ymap(v)) for i, v in enumerate(values)]
    parts.append(f'<polyline points="{" ".join(f"{x:.1f},{y:.1f}" for x, y in pts)}" fill="none" stroke="{color}" stroke-width="2" stroke-linejoin="round"/>')
    for (x, y), v, cat in zip(pts, values, categories):
        parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="5" fill="{color}" stroke="{SURFACE}" stroke-width="2"><title>{esc(cat)}：{wan(v)} 萬元</title></circle>')
        parts.append(f'<text x="{x:.1f}" y="{y - 14:.1f}" font-size="14" font-weight="700" fill="{TEXT_1}" text-anchor="middle">{wan(v)}</text>')
        parts.append(f'<text x="{x:.1f}" y="{H - PAD_B + 26}" font-size="15" fill="{TEXT_1}" text-anchor="middle">{esc(cat)}</text>')
    return svg_wrap(parts + footer(note))


# ── DATA（元；出處為 F2 v2.1 章節） ───────────────────────────────────
YEARS = ["第一年", "第二年", "第三年"]

CHARTS = {
    # 參、平台總流水 GMV
    "f2_gmv_by_tier": lambda: stacked_bars(
        YEARS,
        [dict(name="第一層 外展移工", color=S1, values=[6156000, 26680000, 83190000]),
         dict(name="第二層 本土部分工時", color=S2, values=[1200000, 5580000, 16000000]),
         dict(name="第三層 統包代耕", color=S3, values=[775000, 4700000, 16000000])],
        "平台總流水 GMV：三年由 813 萬成長至 1.15 億",
        "依三層人力分列，單位：萬元", total_label="GMV"),
    # 參、科技公司損益
    "f2_tech_pnl": lambda: grouped_bars(
        YEARS,
        [dict(name="營業收入", color=S1, values=[1711080, 8176800, 25874200]),
         dict(name="營業毛利", color=S2, values=[1488080, 7356800, 23424200]),
         dict(name="EBITDA", color=S3, values=[252080, 3516800, 13924200])],
        "科技公司：第一年 EBITDA 25 萬，第三年 1,392 萬",
        "科技公司帳（不含合作社），單位：萬元"),
    # 伍、合併觀點
    "f2_consolidated": lambda: grouped_bars(
        YEARS,
        [dict(name="科技公司 EBITDA", color=S1, values=[252080, 3516800, 13924200]),
         dict(name="合作社本期損益", color=S2, values=[-2585008, -8567600, -19241800]),
         dict(name="合併營運結果", color=S3, values=[-2332928, -5050800, -5317600])],
        "現行定價下，合作社三年皆虧損，合併結果為負",
        "科技公司帳面獲利建立在合作社承擔虧損之上，須以第玖章調整方案解決，單位：萬元"),
    # 柒、期末現金餘額
    "f2_cash_balance": lambda: line_chart(
        ["第0年（籌備）", "第一年", "第二年", "第三年"],
        [430000, 280794, 1909734, 10669270],
        "科技公司期末現金：第一年僅剩約 28 萬元",
        "基本情境（不含補助、不含合作社預收定金），單位：萬元"),
    # 捌、敏感度分析（合併 Y3）
    "f2_sensitivity_y3": lambda: hbars(
        ["基本情境", "利用率 −5 個百分點", "最低工資每年 +3%", "利用率 −10 個百分點",
         "最低工資每年 +5%", "8,800 獎勵由合作社自付"],
        [-5317600, -9654100, -10656070, -13990600, -14302709, -21157600],
        "第三年合併營運結果：工資與利用率風險全落在合作社",
        "各情境下第三年「科技公司 EBITDA + 合作社損益」，灰色為基本情境",
        highlight=0),
    # 玖、基本情境 vs 建議方案 C1
    "f2_coop_base_vs_c1": lambda: grouped_bars(
        YEARS,
        [dict(name="基本情境（現行定價、18% 代管費）", color=S1, values=[-2585008, -8567600, -19241800]),
         dict(name="建議方案 C1（待經營團隊決定）", color=S2, values=[1592, 1603272, 9152500])],
        "合作社損益：建議方案 C1 可使第一年約損益兩平",
        "C1＝第一層均價 +30 元/hr、交通費實報實銷、師傅 80% 工時計費、代管費率 15%，單位：萬元"),
    # 柒、青創貸款還款（科技公司實際支付）
    "f2_loan_repayment": lambda: stacked_bars(
        ["第1年", "第2年", "第3年", "第4年", "第5年", "第6年"],
        [dict(name="償還本金", color=S1, values=[0, 200000, 193617, 197812, 202097, 206474]),
         dict(name="公司負擔利息", color=S2, values=[0, 0, 15264, 11070, 6785, 2407])],
        "青創貸款 100 萬元：第 1 年寬限、第 2 年起每年還款約 20~21 萬",
        "前 2 年利息假設由政府補貼【待確認：補貼年限 2 年或 5 年】，單位：萬元",
        total_label="",
        note="資料來源：F2 財務計畫報表 2（v2.1）第柒章 3 節，年息約 2.145%，6 年 72 期"),
}


def render(name, svg):
    OUT.mkdir(parents=True, exist_ok=True)
    svg_path = OUT / f"{name}.svg"
    svg_path.write_text(svg, encoding="utf-8")
    page = OUT / f"_{name}.html"
    page.write_text(f'<!doctype html><meta charset="utf-8"><style>html,body{{margin:0;background:{SURFACE}}}</style>{svg}',
                    encoding="utf-8")
    png = OUT / f"{name}.png"
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    f"--window-size={W},{H}", "--force-device-scale-factor=2",
                    f"--screenshot={png}", page.as_uri()],
                   check=True, capture_output=True, timeout=120)
    page.unlink()
    return png


if __name__ == "__main__":
    for name, build in CHARTS.items():
        print("OK", render(name, build()).name)
