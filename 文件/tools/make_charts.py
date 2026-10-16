# -*- coding: utf-8 -*-
"""產生財務文件用圖表（SVG → PNG，以 Edge 或 Chrome headless 轉檔，不需額外套件）。

數字來源：02_財務模型與政府補助/03_財務計畫報表2_…（F2 v2.4 財務定版）。
F2 數字變動時，更新下方 DATA 後重新執行：
    python 文件/tools/make_charts.py [圖名 ...]   # 不帶參數則全部重產
輸出：02_財務模型與政府補助/charts/*.svg、*.png
配色：dataviz 參考色盤淺色模式前 3 槽（已以 validate_palette 驗證）；
aqua 對白底對比 < 3:1，故每張圖都有數值標籤，文件中亦保留數據表。
"""
import html
import sys
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "02_財務模型與政府補助" / "charts"
BROWSERS = [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Google\Chrome\Application\chrome.exe"]

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

SOURCE = "資料來源：F2 財務計畫報表 2（v2.4 財務定版），基本情境採方案 C2、不含政府補助、平台不承擔宿舍與專車"
SOURCE_V23 = "資料來源：F2 財務計畫報表 2（v2.4）第捌、玖章，C2 之定價與人力配置尚待市場驗證"


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


def grouped_bars(categories, series, title, subtitle, unit="萬元", note=SOURCE):
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
    return svg_wrap(parts + footer(note))


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


# ── DATA（元；出處為 F2 v2.4 章節） ───────────────────────────────────
YEARS = ["第一年", "第二年", "第三年"]

CHARTS = {
    # 參、平台總流水 GMV
    "f2_gmv_by_tier": lambda: stacked_bars(
        YEARS,
        [dict(name="第一層 外展移工", color=S1, values=[7257600, 32266240, 98982000]),
         dict(name="第二層 本土部分工時", color=S2, values=[1800000, 8370000, 24000000]),
         dict(name="第三層 統包代耕", color=S3, values=[775000, 4700000, 16000000])],
        "平台總流水 GMV：三年由 983 萬成長至 1.39 億",
        "依三層人力分列，單位：萬元", total_label="GMV"),
    # 參、科技公司損益
    "f2_tech_pnl": lambda: grouped_bars(
        YEARS,
        [dict(name="營業收入", color=S1, values=[1836216, 8871798, 27697120]),
         dict(name="營業毛利", color=S2, values=[1613216, 8051798, 25247120]),
         dict(name="EBITDA", color=S3, values=[317216, 4061798, 15347120])],
        "科技公司：第一年 EBITDA 32 萬，第三年 1,535 萬",
        "科技公司帳（不含合作社），單位：萬元"),
    # 伍、合併觀點
    "f2_consolidated": lambda: grouped_bars(
        YEARS,
        [dict(name="科技公司 EBITDA", color=S1, values=[317216, 4061798, 15347120]),
         dict(name="合作社本期損益", color=S2, values=[175056, 2123842, 10736280]),
         dict(name="合併營運結果", color=S3, values=[492272, 6185640, 26083400])],
        "採方案 C2 後，科技公司、合作社與合併結果三年皆為正",
        "合作社第一年結餘僅約 17.5 萬元，對利用率高度敏感，單位：萬元"),
    # 柒、期末現金餘額
    "f2_cash_balance": lambda: line_chart(
        ["第0年（籌備）", "第一年", "第二年", "第三年"],
        [730000, 622475, 2639925, 12443803],
        "科技公司期末現金：第一年期末約 62 萬元",
        "基本情境（不含補助、不含合作社預收定金），單位：萬元"),
    # 捌、敏感度分析（合併 Y3）
    "f2_sensitivity_y3": lambda: hbars(
        ["基本情境（C2）", "利用率 −5 個百分點", "最低工資每年 +3%（未連動）", "三項【待確認】同時成立",
         "最低工資每年 +5%（未連動）", "利用率 −10 個百分點", "8,800 獎勵由合作社自付"],
        [26083400, 20923700, 20605287, 18638150, 16863258, 15764000, 10243400],
        "第三年合併營運結果：各情境皆為正，8,800 獎勵自付影響最大",
        "各情境下第三年「科技公司 EBITDA + 合作社損益」，灰色為基本情境",
        highlight=0),
    # 玖、v2.3 基本情境 vs C1 vs v2.4 基本情境 C2
    "f2_coop_c2": lambda: grouped_bars(
        YEARS,
        [dict(name="v2.3 基本情境（現行定價）", color=S1, values=[-1397008, -4313600, -7523800]),
         dict(name="替代方案 C1", color=S2, values=[145592, 2203272, 10952500]),
         dict(name="v2.4 基本情境（C2）", color=S3, values=[175056, 2123842, 10736280])],
        "合作社損益：C2 改列基本情境，三年皆有結餘",
        "C2＝均價 +30、師傅 80% 計費、師傅 1:10＋移工組長、代管費 16%、第二層 +50%、住宿預備金，單位：萬元",
        note=SOURCE_V23),
    # 捌、第一年合作社壓力測試（v2.4）
    "f2_c2_stress_y1": lambda: hbars(
        ["基本情境（C2）", "兼職須投勞健保", "年工時降為約 1,904 小時", "移工須提繳勞退 6%",
         "利用率 −5 個百分點", "年工時、兼職投保、勞退同時成立", "8,800 獎勵由合作社自付"],
        [175056, 61764, 2325, -79824, -156858, -365847, -1092144],
        "第一年合作社最脆弱：利用率 −5 個百分點或須提繳勞退即轉虧",
        "v2.4 基本情境（C2）下各情境之合作社第一年損益，灰色為基本情境",
        highlight=0, note=SOURCE_V23),
    # 柒、青創貸款還款（科技公司實際支付）
    "f2_loan_repayment": lambda: stacked_bars(
        ["第1年", "第2年", "第3年", "第4年", "第5年", "第6年"],
        [dict(name="償還本金", color=S1, values=[0, 200000, 193617, 197812, 202097, 206474]),
         dict(name="公司負擔利息", color=S2, values=[0, 0, 15264, 11070, 6785, 2407])],
        "青創貸款 100 萬元：第 1 年寬限、第 2 年起每年還款約 20~21 萬",
        "前 2 年利息假設由政府補貼【待確認：補貼年限 2 年或 5 年】，單位：萬元",
        total_label="",
        note="資料來源：F2 財務計畫報表 2（v2.4）第柒章 3 節，年息約 2.145%，6 年 72 期"),
}

OUT_PITCH = ROOT / "06_簡報與路演Pitch" / "charts"


def build_p03_pain_to_solution():
    """一頁式提案圖 1：三方結構性痛點 ➔ 雙軌合規與三層人力池 ➔ 平台四大破局效益（1200x330）"""
    w, h = 1200, 330
    p = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="{FONT}">',
        f'<rect width="{w}" height="{h}" rx="10" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.5"/>',
        # Top Header Bar
        '<rect x="0" y="0" width="1200" height="38" rx="10" fill="#0F172A"/>',
        '<rect x="0" y="20" width="1200" height="18" fill="#0F172A"/>',
        '<text x="18" y="25" font-size="15" font-weight="700" fill="#FFFFFF">圖 1｜系統化破局邏輯：從「選民與移工三方痛點」到「合作社 ╳ 科技公司雙軌 AI 動態調度」</text>',
        '<text x="1182" y="25" font-size="12" fill="#94A3B8" text-anchor="end">2026 最低工資基準：月薪 $29,500／時薪 $196（F2 v2.4）</text>',

        # ── Column 1: 痛點診斷 (x=14..374, w=360, y=48..318, h=270) ──
        '<rect x="14" y="48" width="360" height="270" rx="8" fill="#FFFFFF" stroke="#FCA5A5" stroke-width="1.5"/>',
        '<rect x="14" y="48" width="360" height="32" rx="8" fill="#DC2626"/>',
        '<rect x="14" y="68" width="360" height="12" fill="#DC2626"/>',
        '<text x="194" y="69" font-size="14" font-weight="700" fill="#FFFFFF" text-anchor="middle">① 痛點診斷：農民（選民）、移工與外展困局</text>',

        # Sub-box 1A: 農民（選民）痛點
        '<rect x="24" y="88" width="340" height="110" rx="6" fill="#FEF2F2" stroke="#FECACA" stroke-width="1"/>',
        '<text x="32" y="108" font-size="13" font-weight="700" fill="#991B1B">🌾 農場與農民（選民）四大痛點</text>',
        '<text x="32" y="128" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#B91C1C">產季被放鳥</tspan>：口頭約工無保障，加價被截工、落果損失慘</text>',
        '<text x="32" y="147" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#B91C1C">請黑工重罰</tspan>：冒險聘失聯移工，面臨就服法 <tspan font-weight="700">15～75萬罰鍰</tspan></text>',
        '<text x="32" y="166" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#B91C1C">外銷果損退貨</tspan>：臨時工蹭掉果粉破蒂，無發票難過通路稽核</text>',
        '<text x="32" y="185" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#B91C1C">梅雨停工損失</tspan>：下雨泥濘停工白付工資，放晴後工人失聯</text>',

        # Sub-box 1B: 移工與外展痛點
        '<rect x="24" y="204" width="340" height="106" rx="6" fill="#FFF7ED" stroke="#FED7AA" stroke-width="1"/>',
        '<text x="32" y="224" font-size="13" font-weight="700" fill="#9A3412">👷 移工與傳統外展機構三大困境</text>',
        '<text x="32" y="244" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#C2410C">淡季閒置虧損</tspan>：月法定成本 <tspan font-weight="700">$36,312/人</tspan>，雨天或淡季即虧</text>',
        '<text x="32" y="263" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#C2410C">語言農藝斷層</tspan>：越/印語不通、不懂留蒂規範，頻遭果農抱怨</text>',
        '<text x="32" y="282" font-size="11.5" fill="#1E293B">• <tspan font-weight="700" fill="#C2410C">地下牛頭剝削</tspan>：失聯移工遭抽頭、無職保，衍生治安黑數</text>',
        '<text x="32" y="301" font-size="10.5" fill="#64748B">＊單一雇主制僵化 vs. 農業季節性尖峰需求之結構矛盾</text>',

        # Arrow 1 -> 2
        '<polygon points="379,175 395,175 395,167 409,183 395,199 395,191 379,191" fill="#059669"/>',

        # ── Column 2: 雙軌架構與三層人力池 (x=414..806, w=392, y=48..318, h=270) ──
        '<rect x="414" y="48" width="392" height="270" rx="8" fill="#FFFFFF" stroke="#6EE7B7" stroke-width="1.5"/>',
        '<rect x="414" y="48" width="392" height="32" rx="8" fill="#059669"/>',
        '<rect x="414" y="68" width="392" height="12" fill="#059669"/>',
        '<text x="610" y="69" font-size="14" font-weight="700" fill="#FFFFFF" text-anchor="middle">② 平台核心：雙軌合規架構 ╳ 三層混合人力池</text>',

        # Dual Entity Boxes (left x=424..580 w=156, gap x=580..640 w=60, right x=640..796 w=156)
        '<rect x="424" y="88" width="156" height="98" rx="6" fill="#ECFDF5" stroke="#34D399" stroke-width="1.2"/>',
        '<text x="502" y="107" font-size="12.5" font-weight="700" fill="#065F46" text-anchor="middle">🏛️ 農業勞務合作社</text>',
        '<text x="502" y="124" font-size="11" font-weight="700" fill="#047857" text-anchor="middle">【法定雇主・合規保護傘】</text>',
        '<text x="432" y="143" font-size="11" fill="#1E293B">• 申請農業部外展配額</text>',
        '<text x="432" y="160" font-size="11" fill="#1E293B">• 100% 職保與合法發票</text>',
        '<text x="432" y="177" font-size="11" fill="#1E293B">• 預收 20% 定金穩金流</text>',

        # Center Link between Coop & Tech (x=580..640, center=610)
        '<line x1="586" y1="135" x2="634" y2="135" stroke="#059669" stroke-width="2"/>',
        '<polygon points="630,130 638,135 630,140" fill="#059669"/>',
        '<polygon points="590,130 582,135 590,140" fill="#059669"/>',
        '<text x="610" y="123" font-size="10.5" font-weight="700" fill="#065F46" text-anchor="middle">5年協議</text>',
        '<text x="610" y="152" font-size="10.5" font-weight="700" fill="#065F46" text-anchor="middle">16%代管</text>',

        '<rect x="640" y="88" width="156" height="98" rx="6" fill="#EFF6FF" stroke="#60A5FA" stroke-width="1.2"/>',
        '<text x="718" y="107" font-size="12.5" font-weight="700" fill="#1E40AF" text-anchor="middle">💻 智農科技公司</text>',
        '<text x="718" y="124" font-size="11" font-weight="700" fill="#1D4ED8" text-anchor="middle">【AI 調度大腦・營運代管】</text>',
        '<text x="648" y="143" font-size="11" fill="#1E293B">• 跨產曆與微氣象雷達</text>',
        '<text x="648" y="160" font-size="11" fill="#1E293B">• LINE 雙帳號免裝 App</text>',
        '<text x="648" y="177" font-size="11" fill="#1E293B">• 中/越/印三語語音工單</text>',

        # Three-Tier Labor Pool
        '<rect x="424" y="194" width="372" height="116" rx="6" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.2"/>',
        '<text x="610" y="213" font-size="12.5" font-weight="700" fill="#0F172A" text-anchor="middle">⚡ 三層混合人力池（削峰填谷・兼顧穩定度與高毛利）</text>',

        '<rect x="432" y="222" width="114" height="80" rx="5" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1"/>',
        '<text x="489" y="239" font-size="11.5" font-weight="700" fill="#047857" text-anchor="middle">第一層｜外展移工</text>',
        '<text x="489" y="256" font-size="10.5" fill="#334155" text-anchor="middle">全職核心 (12➔150人)</text>',
        '<text x="489" y="272" font-size="10.5" fill="#334155" text-anchor="middle">收費 $315~$325/hr</text>',
        '<text x="489" y="288" font-size="10" font-weight="700" fill="#059669" text-anchor="middle">師傅1:10＋移工組長</text>',

        '<rect x="553" y="222" width="114" height="80" rx="5" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1"/>',
        '<text x="610" y="239" font-size="11.5" font-weight="700" fill="#D97706" text-anchor="middle">第二層｜本土兼職</text>',
        '<text x="610" y="256" font-size="10.5" fill="#334155" text-anchor="middle">中高齡/青年 (時薪)</text>',
        '<text x="610" y="272" font-size="10.5" fill="#334155" text-anchor="middle">收費 $300~$320/hr</text>',
        '<text x="610" y="288" font-size="10" font-weight="700" fill="#B45309" text-anchor="middle">出勤才計薪・毛利30%+</text>',

        '<rect x="674" y="222" width="114" height="80" rx="5" fill="#FFFFFF" stroke="#94A3B8" stroke-width="1"/>',
        '<text x="731" y="239" font-size="11.5" font-weight="700" fill="#1D4ED8" text-anchor="middle">第三層｜統包代耕</text>',
        '<text x="731" y="256" font-size="10.5" fill="#334155" text-anchor="middle">機耕/無人機 (承攬)</text>',
        '<text x="731" y="272" font-size="10.5" fill="#334155" text-anchor="middle">自備機具・按面積計</text>',
        '<text x="731" y="288" font-size="10" font-weight="700" fill="#1E40AF" text-anchor="middle">平台價差 34%~36%</text>',

        # Arrow 2 -> 3
        '<polygon points="811,175 827,175 827,167 841,183 827,199 827,191 811,191" fill="#1D4ED8"/>',

        # ── Column 3: 四大破局功能與效益 (x=846..1186, w=340, y=48..318, h=270) ──
        '<rect x="846" y="48" width="340" height="270" rx="8" fill="#FFFFFF" stroke="#93C5FD" stroke-width="1.5"/>',
        '<rect x="846" y="48" width="340" height="32" rx="8" fill="#1D4ED8"/>',
        '<rect x="846" y="68" width="340" height="12" fill="#1D4ED8"/>',
        '<text x="1016" y="69" font-size="14" font-weight="700" fill="#FFFFFF" text-anchor="middle">③ 解決方案：四大系統功能與量化效益</text>',

        # 4 Feature Cards
        '<rect x="856" y="88" width="320" height="51" rx="6" fill="#EFF6FF" stroke="#BFDBFE" stroke-width="1"/>',
        '<text x="864" y="107" font-size="12" font-weight="700" fill="#1E3A8A">🛡️ 1. 早鳥儲值工時包（破除產季放鳥）</text>',
        '<text x="864" y="127" font-size="11" fill="#1E293B">產前付定 20% 鎖定名額，合約明定<tspan font-weight="700" fill="#1D4ED8">遲到免單、放鳥雙倍賠</tspan></text>',

        '<rect x="856" y="144" width="320" height="51" rx="6" fill="#ECFDF5" stroke="#A7F3D0" stroke-width="1"/>',
        '<text x="864" y="163" font-size="12" font-weight="700" fill="#065F46">🌦️ 2. 微氣象雷達 ╳ 冷鏈備援（破除閒置虧損）</text>',
        '<text x="864" y="183" font-size="11" fill="#1E293B">串接 368 鄉鎮預報，豪雨改派包裝廠，<tspan font-weight="700" fill="#047857">目標稼動率 92%</tspan></text>',

        '<rect x="856" y="200" width="320" height="51" rx="6" fill="#FEF3C7" stroke="#FDE68A" stroke-width="1"/>',
        '<text x="864" y="219" font-size="12" font-weight="700" fill="#92400E">🗣️ 3. LINE 免裝 App ╳ 三語 SOP（破除果損與落差）</text>',
        '<text x="864" y="239" font-size="11" fill="#1E293B">農民 LINE 一鍵叫工；越/印語音留蒂 0.5cm，<tspan font-weight="700" fill="#B45309">良率 ≥92%</tspan></text>',

        '<rect x="856" y="256" width="320" height="54" rx="6" fill="#F3E8FF" stroke="#DDD6FE" stroke-width="1"/>',
        '<text x="864" y="275" font-size="12" font-weight="700" fill="#5B21B6">📑 4. 合法發票 ╳ 職保名冊（破除 75萬罰鍰與退貨）</text>',
        '<text x="864" y="295" font-size="11" fill="#1E293B">農民 <tspan font-weight="700" fill="#6D28D9">0 罰鍰風險</tspan>，助產銷班通過連鎖超市與日韓外銷稽核</text>',

        '</svg>'
    ]
    return "\n".join(p), w, h


def build_p03_governance_kpi():
    """一頁式提案圖 2：民代與縣府四大宣傳政績 ╳ 三年產區服務規模與自給效益（1200x300）"""
    w, h = 1200, 300
    p = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="{FONT}">',
        f'<rect width="{w}" height="{h}" rx="10" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.5"/>',
        # Top Header Bar
        '<rect x="0" y="0" width="1200" height="36" rx="10" fill="#065F46"/>',
        '<rect x="0" y="20" width="1200" height="16" fill="#065F46"/>',
        '<text x="18" y="24" font-size="15" font-weight="700" fill="#FFFFFF">圖 2｜施政效益與政績亮點：四大有感問政成果 ╳ 示範基地三年量化推演（F2 v2.4 基本情境 C2）</text>',
        '<text x="1182" y="24" font-size="12" fill="#A7F3D0" text-anchor="end">民間雙軌自給自足・基本情境不含政府補助</text>',

        # ── Left Panel: 四大宣傳政績 (x=14..646, w=632, y=46..288, h=242) ──
        '<rect x="14" y="46" width="632" height="242" rx="8" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="1.2"/>',
        '<text x="26" y="68" font-size="13.5" font-weight="700" fill="#0F172A">🏛️ 民意代表與縣市政府可對外發布之「四大宣傳政績」</text>',

        # Card 1 (Top-Left)
        '<rect x="24" y="78" width="298" height="98" rx="6" fill="#FFFBEB" stroke="#F59E0B" stroke-width="1.5"/>',
        '<text x="32" y="98" font-size="12.5" font-weight="700" fill="#B45309">🏆 政績 1｜護農增收・選民服務最有感</text>',
        '<text x="32" y="117" font-size="11.5" font-weight="700" fill="#0F172A">「水果紅了、工人到了！終結放鳥與75萬罰單」</text>',
        '<text x="32" y="136" font-size="11" fill="#334155">• 解決選區果農產季缺工爛果與挨罰恐懼</text>',
        '<text x="32" y="154" font-size="11" fill="#334155">• 產銷班座談、農民節與問政報告最響亮民生捷報</text>',

        # Card 2 (Top-Right)
        '<rect x="334" y="78" width="300" height="98" rx="6" fill="#ECFDF5" stroke="#10B981" stroke-width="1.5"/>',
        '<text x="342" y="98" font-size="12.5" font-weight="700" fill="#047857">🚀 政績 2｜全台首創・智慧農業數位標竿</text>',
        '<text x="342" y="117" font-size="11.5" font-weight="700" fill="#0F172A">「AI 微氣象避險 ╳ LINE 三語派工示範縣市」</text>',
        '<text x="342" y="136" font-size="11" fill="#334155">• 老農免裝 App 用 LINE 叫工、豪雨自動改派冷鏈</text>',
        '<text x="342" y="154" font-size="11" fill="#334155">• 樹立縣政白皮書數位轉型亮點與中央部會觀摩典範</text>',

        # Card 3 (Bottom-Left)
        '<rect x="24" y="182" width="298" height="98" rx="6" fill="#EFF6FF" stroke="#3B82F6" stroke-width="1.5"/>',
        '<text x="32" y="202" font-size="12.5" font-weight="700" fill="#1D4ED8">🌍 政績 3｜外銷升級・接軌國際 ESG 通路</text>',
        '<text x="32" y="221" font-size="11.5" font-weight="700" fill="#0F172A">「100% 合法發票與職保・打通外銷契作護城河」</text>',
        '<text x="32" y="240" font-size="11" fill="#334155">• 助芒果、蓮霧、蜜棗、文旦、茶葉過連鎖超市稽核</text>',
        '<text x="32" y="258" font-size="11" fill="#334155">• 留蒂 0.5cm 護果粉 SOP，守住日韓外銷高單價溢價</text>',

        # Card 4 (Bottom-Right)
        '<rect x="334" y="182" width="300" height="98" rx="6" fill="#F5F3FF" stroke="#8B5CF6" stroke-width="1.5"/>',
        '<text x="342" y="202" font-size="12.5" font-weight="700" fill="#6D28D9">🛡️ 政績 4｜零錢坑永續・消弭黑工治安黑數</text>',
        '<text x="342" y="221" font-size="11.5" font-weight="700" fill="#0F172A">「正規軍取代地下牛頭・公私協力自給自足」</text>',
        '<text x="342" y="240" font-size="11" fill="#334155">• 活化公有閒置場館（OT），改善失聯移工治安與職災</text>',
        '<text x="342" y="258" font-size="11" fill="#334155">• 約 7～8 名移工即損益兩平，不留政府長期財政負擔</text>',

        # ── Right Panel: 三年產區服務規模與財務自給推演 (x=658..1186, w=528, y=46..288, h=242) ──
        '<rect x="658" y="46" width="528" height="242" rx="8" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="1.2"/>',
        '<text x="670" y="68" font-size="13.5" font-weight="700" fill="#0F172A">📊 示範基地三年產區效益推演（單位：萬元｜F2 v2.4）</text>',

        # Legend
        '<rect x="670" y="78" width="11" height="11" rx="2" fill="#2A78D6"/>',
        '<text x="685" y="88" font-size="11" fill="#1E293B">產區服務總流水 (GMV)</text>',
        '<rect x="814" y="78" width="11" height="11" rx="2" fill="#1BAF7A"/>',
        '<text x="829" y="88" font-size="11" fill="#1E293B">合併營運結果 (科技EBITDA+合作社結餘)</text>',
        '<rect x="1048" y="78" width="11" height="11" rx="2" fill="#EB6834"/>',
        '<text x="1063" y="88" font-size="11" fill="#1E293B">合作社結餘</text>',

        # Horizontal Table + Visual Bars for Y1, Y2, Y3
        # Header Row
        '<rect x="670" y="96" width="504" height="24" rx="4" fill="#F1F5F9"/>',
        '<text x="678" y="112" font-size="11" font-weight="700" fill="#334155">期程與人力規模</text>',
        '<text x="818" y="112" font-size="11" font-weight="700" fill="#334155">合法工時／服務戶</text>',
        '<text x="936" y="112" font-size="11" font-weight="700" fill="#1E40AF">產區流水 GMV</text>',
        '<text x="1168" y="112" font-size="11" font-weight="700" fill="#065F46" text-anchor="end">合併結果 (合作社)</text>',

        # Row 1: Year 1
        '<line x1="670" y1="166" x2="1174" y2="166" stroke="#E2E8F0" stroke-width="1"/>',
        '<text x="678" y="138" font-size="11.5" font-weight="700" fill="#0F172A">第一年（灘頭堡示範）</text>',
        '<text x="678" y="156" font-size="10.5" fill="#475569">移工 12 人＋兼職 12 人</text>',
        '<text x="818" y="138" font-size="11.5" font-weight="700" fill="#0F172A">29,040 小時</text>',
        '<text x="818" y="156" font-size="10.5" fill="#475569">12 戶｜稼動率 92%</text>',
        '<rect x="936" y="126" width="12" height="13" rx="2" fill="#2A78D6"/>',
        '<text x="954" y="138" font-size="12" font-weight="700" fill="#1E40AF">983.3 萬</text>',
        '<text x="936" y="156" font-size="10.5" fill="#475569">科技營收 183.6 萬</text>',
        '<text x="1168" y="138" font-size="12" font-weight="700" fill="#047857" text-anchor="end">+49.2 萬</text>',
        '<text x="1168" y="156" font-size="10.5" fill="#C2410C" text-anchor="end">(合作社 +17.5萬)</text>',

        # Row 2: Year 2
        '<line x1="670" y1="214" x2="1174" y2="214" stroke="#E2E8F0" stroke-width="1"/>',
        '<text x="678" y="186" font-size="11.5" font-weight="700" fill="#0F172A">第二年（跨產區輪轉）</text>',
        '<text x="678" y="204" font-size="10.5" fill="#475569">移工 50 人＋兼職 45 人</text>',
        '<text x="818" y="186" font-size="11.5" font-weight="700" fill="#0F172A">127,832 小時</text>',
        '<text x="818" y="204" font-size="10.5" fill="#475569">75 戶｜稼動率 94%</text>',
        '<rect x="936" y="174" width="26" height="13" rx="2" fill="#2A78D6"/>',
        '<text x="968" y="186" font-size="12" font-weight="700" fill="#1E40AF">4,533.6 萬</text>',
        '<text x="936" y="204" font-size="10.5" fill="#475569">科技營收 887.2 萬</text>',
        '<text x="1168" y="186" font-size="12" font-weight="700" fill="#047857" text-anchor="end">+618.6 萬</text>',
        '<text x="1168" y="204" font-size="10.5" fill="#C2410C" text-anchor="end">(合作社 +212.4萬)</text>',

        # Row 3: Year 3
        '<line x1="670" y1="262" x2="1174" y2="262" stroke="#E2E8F0" stroke-width="1"/>',
        '<text x="678" y="234" font-size="11.5" font-weight="700" fill="#0F172A">第三年（全縣規模化）</text>',
        '<text x="678" y="252" font-size="10.5" fill="#475569">移工 150 人＋兼職 120 人</text>',
        '<text x="818" y="234" font-size="11.5" font-weight="700" fill="#0F172A">379,560 小時</text>',
        '<text x="818" y="252" font-size="10.5" fill="#475569">260 戶｜稼動率 96%</text>',
        '<rect x="936" y="222" width="46" height="13" rx="2" fill="#2A78D6"/>',
        '<text x="988" y="234" font-size="12" font-weight="700" fill="#1E40AF">1.39 億</text>',
        '<text x="936" y="252" font-size="10.5" fill="#475569">科技營收 2,769.7 萬</text>',
        '<text x="1168" y="234" font-size="12" font-weight="700" fill="#047857" text-anchor="end">+2,608.3 萬</text>',
        '<text x="1168" y="252" font-size="10.5" fill="#C2410C" text-anchor="end">(合作社 +1,073.6萬)</text>',
        '<text x="670" y="279" font-size="10" fill="#64748B">⚠️ 揭露：首年合作社損益率 2.3%（利用率 −5% 即轉虧）；C2 定價與配置待市場驗證【待確認】</text>',
        '</svg>'
    ]
    return "\n".join(p), w, h


def build_p03_summary_banner():
    """簡報用簡化全景橫幅：四大核心步驟（痛點 ➔ 解方 ➔ 支持 ➔ 政績），大字體高清晰（1200x200）"""
    w, h = 1200, 200
    p = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" font-family="{FONT}">',
        f'<rect width="{w}" height="{h}" rx="8" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.2"/>',
        # Top ribbon
        '<rect x="0" y="0" width="1200" height="28" rx="8" fill="#0F172A"/>',
        '<rect x="0" y="16" width="1200" height="12" fill="#0F172A"/>',
        '<text x="18" y="19" font-size="13" font-weight="700" fill="#FFFFFF">智農派工 (AgriDispatch) ╳ 縣市政府・民意代表 3 分鐘公私協力全景速覽</text>',
        '<text x="1182" y="19" font-size="11.5" fill="#94A3B8" text-anchor="end">合作社法定雇主 ╳ 科技公司AI調度</text>',

        # 4 Cards side by side (w=265 each: c1=16..281, c2=317..582, c3=618..883, c4=919..1184)
        # Card 1: 痛點 (x=16..281)
        '<rect x="16" y="38" width="265" height="150" rx="6" fill="#FEF2F2" stroke="#FCA5A5" stroke-width="1.2"/>',
        '<rect x="16" y="38" width="265" height="30" rx="6" fill="#DC2626"/>',
        '<rect x="16" y="56" width="265" height="12" fill="#DC2626"/>',
        '<text x="148" y="59" font-size="13.5" font-weight="700" fill="#FFFFFF" text-anchor="middle">① 選民（農民）痛點</text>',
        '<text x="26" y="92" font-size="12.5" font-weight="700" fill="#991B1B">🚨 產季缺工・私人工頭放鳥</text>',
        '<text x="26" y="112" font-size="11.5" fill="#334155">熟果來不及採收，落果損失慘重</text>',
        '<text x="26" y="137" font-size="12.5" font-weight="700" fill="#991B1B">⚖️ 冒險聘黑工・重罰75萬</text>',
        '<text x="26" y="157" font-size="11.5" fill="#334155">面臨《就服法》罰鍰，提心吊膽</text>',
        '<text x="26" y="177" font-size="10.5" fill="#64748B">傳統外展淡季閒置，每人月成本3.6萬</text>',

        # Arrow 1->2
        '<polygon points="289,113 303,113 303,106 313,120 303,134 303,127 289,127" fill="#059669"/>',

        # Card 2: 解方 (x=317..582)
        '<rect x="317" y="38" width="265" height="150" rx="6" fill="#ECFDF5" stroke="#6EE7B7" stroke-width="1.2"/>',
        '<rect x="317" y="38" width="265" height="30" rx="6" fill="#059669"/>',
        '<rect x="317" y="56" width="265" height="12" fill="#059669"/>',
        '<text x="449" y="59" font-size="13.5" font-weight="700" fill="#FFFFFF" text-anchor="middle">② 平台核心解方</text>',
        '<text x="327" y="92" font-size="12.5" font-weight="700" fill="#065F46">🛡️ 早鳥保證出工・放鳥雙倍賠</text>',
        '<text x="327" y="112" font-size="11.5" fill="#334155">產前付定 20% 鎖名額，合約保證</text>',
        '<text x="327" y="137" font-size="12.5" font-weight="700" fill="#065F46">📱 LINE免裝App ╳ 三語語音SOP</text>',
        '<text x="327" y="157" font-size="11.5" fill="#334155">移工聽懂留蒂0.5cm，外銷良率≥92%</text>',
        '<text x="327" y="177" font-size="10.5" fill="#047857">微氣象雷達 ╳ 下雨自動改派冷鏈分級</text>',

        # Arrow 2->3
        '<polygon points="590,113 604,113 604,106 614,120 604,134 604,127 590,127" fill="#1D4ED8"/>',

        # Card 3: 支持 (x=618..883)
        '<rect x="618" y="38" width="265" height="150" rx="6" fill="#EFF6FF" stroke="#93C5FD" stroke-width="1.2"/>',
        '<rect x="618" y="38" width="265" height="30" rx="6" fill="#1D4ED8"/>',
        '<rect x="618" y="56" width="265" height="12" fill="#1D4ED8"/>',
        '<text x="750" y="59" font-size="13.5" font-weight="700" fill="#FFFFFF" text-anchor="middle">③ 公私協力支持模式</text>',
        '<text x="628" y="92" font-size="12.5" font-weight="700" fill="#1E40AF">🏛️ 方案A：行政協作（零預算）</text>',
        '<text x="628" y="112" font-size="11.5" fill="#334155">議會提案示範區，縣府輔導外展資格</text>',
        '<text x="628" y="137" font-size="12.5" font-weight="700" fill="#1E40AF">💰 方案B：採購／地方SBIR補助</text>',
        '<text x="628" y="157" font-size="11.5" fill="#334155">比照農機補助，補貼農民工時費用</text>',
        '<text x="628" y="177" font-size="10.5" fill="#1D4ED8">方案C：公辦民營OT活化閒置公產宿舍</text>',

        # Arrow 3->4
        '<polygon points="891,113 905,113 905,106 915,120 905,134 905,127 891,127" fill="#7C3AED"/>',

        # Card 4: 政績 (x=919..1184)
        '<rect x="919" y="38" width="265" height="150" rx="6" fill="#FAF5FF" stroke="#C4B5FD" stroke-width="1.2"/>',
        '<rect x="919" y="38" width="265" height="30" rx="6" fill="#7C3AED"/>',
        '<rect x="919" y="56" width="265" height="12" fill="#7C3AED"/>',
        '<text x="1051" y="59" font-size="13.5" font-weight="700" fill="#FFFFFF" text-anchor="middle">④ 四大施政宣傳政績</text>',
        '<text x="929" y="92" font-size="12.5" font-weight="700" fill="#5B21B6">🏆 護農鐵票：終結放鳥與罰單</text>',
        '<text x="929" y="112" font-size="11.5" fill="#334155">「水果紅了工人到了」問政最響亮</text>',
        '<text x="929" y="137" font-size="12.5" font-weight="700" fill="#5B21B6">🚀 全台首創智慧外展示範縣市</text>',
        '<text x="929" y="157" font-size="11.5" fill="#334155">100%發票與職保，助攻外銷ESG</text>',
        '<text x="929" y="177" font-size="10.5" fill="#6D28D9">民間自負盈虧，不留政府財政錢坑</text>',

        '</svg>'
    ]
    return "\n".join(p), w, h


PITCH_CHARTS = {
    "p03_summary_banner": build_p03_summary_banner,
    "p03_pain_to_solution": build_p03_pain_to_solution,
    "p03_governance_kpi": build_p03_governance_kpi,
}


def render(name, svg, work, out_dir=OUT, w=W, h=H):
    out_dir.mkdir(parents=True, exist_ok=True)
    svg_path = out_dir / f"{name}.svg"
    svg_path.write_text(svg, encoding="utf-8")
    # 瀏覽器 headless 對含中文的路徑會靜默失敗（回傳 0 卻未寫檔），故在 ASCII 暫存資料夾轉檔後再複製回來
    page = work / f"{name}.html"
    page.write_text(f'<!doctype html><meta charset="utf-8"><style>html,body{{margin:0;background:{SURFACE}}}</style>{svg}',
                    encoding="utf-8")
    tmp_png = work / f"{name}.png"
    for i, exe in enumerate(BROWSERS):
        if not Path(exe).exists():
            continue
        subprocess.run([exe, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        f"--window-size={w},{h}", "--force-device-scale-factor=2",
                        f"--user-data-dir={work / f'profile{i}'}",   # Edge 與 Chrome 不可共用 profile
                        f"--screenshot={tmp_png}", page.as_uri()],
                       capture_output=True, timeout=120, stdin=subprocess.DEVNULL)
        if tmp_png.exists():
            break
    if not tmp_png.exists():
        raise RuntimeError(f"PNG 轉檔失敗：{name}")
    png = out_dir / f"{name}.png"
    png.write_bytes(tmp_png.read_bytes())
    return png


if __name__ == "__main__":
    import shutil
    import tempfile
    work = Path(tempfile.mkdtemp(prefix="agri_charts_"))
    try:
        only = set(sys.argv[1:])   # 可指定圖名，只重產部分圖表
        for name, build in CHARTS.items():
            if only and name not in only:
                continue
            print("OK", render(name, build(), work, OUT, W, H).name)
        for name, build in PITCH_CHARTS.items():
            if only and name not in only:
                continue
            svg_str, cw, ch = build()
            print("OK", render(name, svg_str, work, OUT_PITCH, cw, ch).name)
    finally:
        shutil.rmtree(work, ignore_errors=True)

