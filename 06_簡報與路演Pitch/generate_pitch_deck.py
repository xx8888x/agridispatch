import sys
import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

def create_presentation():
    prs = Presentation()
    # 16:9 Widescreen layout
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank_slide_layout = prs.slide_layouts[6]

    # Color Palette
    DARK_BG = RGBColor(15, 23, 42)        # Slate 900
    LIGHT_BG = RGBColor(248, 250, 252)    # Slate 50
    WHITE = RGBColor(255, 255, 255)
    CARD_BG = RGBColor(255, 255, 255)
    CARD_BORDER = RGBColor(226, 232, 240)
    
    PRIMARY_GREEN = RGBColor(16, 149, 93) # Forest Emerald
    ACCENT_GREEN = RGBColor(34, 197, 94)  # Light Green
    MANGO_ORANGE = RGBColor(245, 158, 11) # Mango Amber
    ALERT_RED = RGBColor(239, 68, 68)     # Rose/Red
    TEXT_MAIN = RGBColor(30, 41, 59)      # Slate 800
    TEXT_MUTED = RGBColor(100, 116, 139)  # Slate 500
    TEXT_LIGHT = RGBColor(226, 232, 240)

    FONT_HEADING = "Microsoft JhengHei"
    FONT_BODY = "Microsoft JhengHei"

    def set_bg(slide, color):
        bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(13.333), Inches(7.5))
        bg.fill.solid()
        bg.fill.fore_color.rgb = color
        bg.line.fill.background()
        return bg

    def add_header(slide, title_text, category="智農派工 AgriDispatch", dark=False):
        # Header banner
        cat_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.5), Inches(11.7), Inches(0.35))
        tf_cat = cat_box.text_frame
        tf_cat.word_wrap = True
        p_cat = tf_cat.paragraphs[0]
        p_cat.text = category.upper()
        p_cat.font.size = Pt(11)
        p_cat.font.bold = True
        p_cat.font.color.rgb = PRIMARY_GREEN
        p_cat.font.name = FONT_BODY

        title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.85), Inches(11.7), Inches(0.7))
        tf_title = title_box.text_frame
        tf_title.word_wrap = True
        p_title = tf_title.paragraphs[0]
        p_title.text = title_text
        p_title.font.size = Pt(24)
        p_title.font.bold = True
        p_title.font.color.rgb = WHITE if dark else TEXT_MAIN   # 深色背景頁標題改白字
        p_title.font.name = FONT_HEADING

    def add_card(slide, left, top, width, height, title, content_list, bg_color=CARD_BG, border_color=CARD_BORDER, title_color=PRIMARY_GREEN):
        card = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(left), Inches(top), Inches(width), Inches(height))
        card.fill.solid()
        card.fill.fore_color.rgb = bg_color
        card.line.color.rgb = border_color
        card.line.width = Pt(1.5)

        tb = slide.shapes.add_textbox(Inches(left + 0.25), Inches(top + 0.2), Inches(width - 0.5), Inches(height - 0.4))
        tf = tb.text_frame
        tf.word_wrap = True

        p0 = tf.paragraphs[0]
        p0.text = title
        p0.font.size = Pt(18)
        p0.font.bold = True
        p0.font.color.rgb = title_color
        p0.font.name = FONT_HEADING
        p0.space_after = Pt(12)

        for item in content_list:
            p = tf.add_paragraph()
            if not item:                       # 空字串＝段落間距，不加圓點
                p.font.size = Pt(6)
                continue
            sub = item.startswith("• ")        # 已帶圓點者視為子項目，縮排並改用空心圓點
            p.text = ("◦ " + item[2:]) if sub else ("• " + item)
            # 懸掛縮排：換行後對齊圓點後的文字
            p._p.get_or_add_pPr().set("marL", str(Pt(34 if sub else 14)))
            p._p.get_or_add_pPr().set("indent", str(-Pt(14)))
            p.font.size = Pt(16)
            p.font.color.rgb = TEXT_MAIN
            p.font.name = FONT_BODY
            p.space_after = Pt(8)

        return card

    def set_font(rPr, face):
        """確保 rPr 依 schema 順序含 a:latin 與 a:ea（latin 須在 cs、sym、hlink 等之前）。"""
        latin = rPr.find(qn("a:latin"))
        if latin is None:
            latin = rPr.makeelement(qn("a:latin"), {"typeface": face})
            later = [c for c in rPr if c.tag in {qn("a:ea"), qn("a:cs"), qn("a:sym"), qn("a:hlinkClick"),
                                                  qn("a:hlinkMouseOver"), qn("a:rtl"), qn("a:extLst")}]
            later[0].addprevious(latin) if later else rPr.append(latin)
        ea = rPr.find(qn("a:ea"))
        if ea is None:
            latin.addnext(rPr.makeelement(qn("a:ea"), {"typeface": latin.get("typeface")}))

    def polish_cjk(prs):
        """全份簡報套用中文排版：標點懸尾（避免句號單獨成行）、東亞斷行規則、繁中語系。"""
        for slide in prs.slides:
            for shape in slide.shapes:
                if not shape.has_text_frame:
                    continue
                for para in shape.text_frame.paragraphs:
                    pPr = para._p.get_or_add_pPr()
                    pPr.set("hangingPunct", "1")
                    pPr.set("eaLnBrk", "1")
                    # 語系設為繁中後 PowerPoint 會改用東亞字型（預設新細明體），須同步指定 ea 字型
                    defRPr = pPr.find(qn("a:defRPr"))
                    latin = defRPr.find(qn("a:latin")) if defRPr is not None else None
                    face = latin.get("typeface") if latin is not None else FONT_BODY
                    if defRPr is not None:
                        set_font(defRPr, face)
                    for run in para.runs:
                        rPr = run._r.get_or_add_rPr()
                        rPr.set("lang", "zh-TW")
                        set_font(rPr, face)

    def add_notes(slide, script_text):
        notes_slide = slide.notes_slide
        tf = notes_slide.notes_text_frame
        tf.text = "【講者極速講稿 (Speaker Notes)】\n" + script_text

    # ==========================================
    # SLIDE 1: COVER
    # ==========================================
    slide1 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide1, DARK_BG)

    # Accent graphic line
    line = slide1.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(1.2), Inches(1.8), Inches(1.5), Inches(0.08))
    line.fill.solid()
    line.fill.fore_color.rgb = MANGO_ORANGE
    line.line.fill.background()

    # Title box
    tb = slide1.shapes.add_textbox(Inches(1.2), Inches(2.2), Inches(11.0), Inches(3.0))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "智農派工 AgriDispatch"
    p.font.size = Pt(44)
    p.font.bold = True
    p.font.color.rgb = WHITE
    p.font.name = FONT_HEADING
    p.space_after = Pt(14)

    p2 = tf.add_paragraph()
    p2.text = "合法外展農務動態調度與避險平台"
    p2.font.size = Pt(22)
    p2.font.color.rgb = ACCENT_GREEN
    p2.font.name = FONT_HEADING
    p2.space_after = Pt(24)

    p3 = tf.add_paragraph()
    p3.text = "AI 動態調度  |  雙法人合作架構  |  農業 ESG 供應鏈閉環"
    p3.font.size = Pt(15)
    p3.font.color.rgb = TEXT_LIGHT
    p3.font.name = FONT_BODY

    # Footer presenter info
    tb_foot = slide1.shapes.add_textbox(Inches(1.2), Inches(5.8), Inches(10.0), Inches(0.8))
    tf_foot = tb_foot.text_frame
    p_foot = tf_foot.paragraphs[0]
    p_foot.text = "技術創辦人 (Technical Founder)  |  Garage+ 進駐提案簡報"
    p_foot.font.size = Pt(13)
    p_foot.font.color.rgb = TEXT_MUTED
    p_foot.font.name = FONT_BODY

    add_notes(slide1, "各位評審好，我是創辦人。台灣農業正面臨高齡化缺工與非法用工的雙重問題。今天我帶來的『智農派工』已完成原型開發，用調度演算法與「科技公司 ╳ 農業合作社」雙軌架構，為農業提供合法、可排程的勞務服務。")

    # ==========================================
    # SLIDE 2: THE PROBLEM
    # ==========================================
    slide2 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide2, LIGHT_BG)
    add_header(slide2, "台灣農業的三大痛點：高齡缺工 ╳ 非法用工 ╳ 天候停工")

    add_card(slide2, 0.8, 1.8, 3.6, 5.0, "痛點 1：產季大放鳥", [
        "農業從業人員平均年齡約 64 歲（待補來源：農業部統計）。",
        "非法人力無合約約束，產季出工不穩定。",
        "外銷愛文芒果熟透不及採收，容易軟爛落果。",
        "單季損失可達數十萬元（待補來源）。"
    ], border_color=ALERT_RED, title_color=ALERT_RED)

    add_card(slide2, 4.8, 1.8, 3.6, 5.0, "痛點 2：梅雨停工損失", [
        "5~6 月採收大尖峰適逢台灣連日梅雨豪雨。",
        "果園泥濘無法採摘，雇主仍須支付閒置工資。",
        "缺乏跨區與上下游銜接機制，看天吃飯無從避險。",
        "放晴後人力常已被調走，難以及時補工。"
    ], border_color=MANGO_ORANGE, title_color=MANGO_ORANGE)

    add_card(slide2, 8.8, 1.8, 3.6, 5.0, "痛點 3：法規罰鍰與通路稽核", [
        "非法聘僱外國人可依就服法處 15~75 萬元罰鍰（待確認）。",
        "通路與外銷供應鏈重視勞動合規（待補來源）。",
        "非法用工無合法憑證，難以通過稽核。",
        "可能影響契作資格。"
    ], border_color=PRIMARY_GREEN, title_color=PRIMARY_GREEN)

    add_notes(slide2, "（示例情境）一位屏東枋山的愛文芒果產銷班長，每年產季都擔心叫不到工：非法人力沒有合約約束，隨時可能被別人以較高工資找走；芒果來不及採收就會落果，損失可達數十萬元。非法聘僱還可能面臨就業服務法的罰鍰，並影響通路契作資格。")

    # ==========================================
    # SLIDE 3: THE SOLUTION
    # ==========================================
    slide3 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide3, LIGHT_BG)
    add_header(slide3, "智農派工破局方案：不賣人頭，賣「確定性與外銷品質」")

    add_card(slide3, 0.8, 1.8, 2.7, 5.0, "1. 契約保障出工", [
        "產季早鳥保證工時包 (120H)。",
        "契約明定：遲到免單、放鳥雙倍賠償。",
        "產前 45 天收定 20%，鎖定名額消除心理焦慮。"
    ])

    add_card(slide3, 3.8, 1.8, 2.7, 5.0, "2. 外銷留蒂 SOP", [
        "越/印/中三語圖解語音採收培訓指南。",
        "標準化「留蒂 0.5cm 護果粉」技術，免除乳膠傷果。",
        "目標外銷 A 級品合格率 92%（以驗收紀錄衡量）。"
    ])

    add_card(slide3, 6.8, 1.8, 2.7, 5.0, "3. 梅雨冷鏈避險", [
        "串接中央氣象署降雨雷達回波 API。",
        "遇果園豪雨停工，改派至已簽約冷鏈廠分級。",
        "降低雨天停工損失。"
    ])

    add_card(slide3, 9.8, 1.8, 2.7, 5.0, "4. 合規憑證", [
        "由合作社（法定雇主）開立合法憑證。",
        "協助產銷班完成帳務與稅務憑證。",
        "協助滿足通路供應鏈勞動合規要求。"
    ])

    add_notes(slide3, "智農派工不賣人頭，我們賣的是『確定性與外銷品質』。我們向農民預售『早鳥保證工時包』，在合約寫上遲到免單、放鳥雙倍賠償。遇上連日大雨，系統改派工班到已簽約的室內冷鏈廠分級，降低停工損失；合作社並開立合法憑證，協助農民通過通路稽核。")

    # ==========================================
    # SLIDE 4: LIVE PRODUCT DEMO
    # ==========================================
    slide4 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide4, DARK_BG)
    add_header(slide4, "現場原型展示：手捏可運行的數位戰情室 (Live Demo)", category="PRODUCT & TECHNOLOGY", dark=True)

    # Big highlight center box
    box = slide4.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(1.8), Inches(11.7), Inches(5.0))
    box.fill.solid()
    box.fill.fore_color.rgb = RGBColor(30, 41, 59)
    box.line.color.rgb = PRIMARY_GREEN
    box.line.width = Pt(2)

    tb = slide4.shapes.add_textbox(Inches(1.2), Inches(2.1), Inches(10.9), Inches(4.4))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "【請切換筆電螢幕至展示系統：Software/demo/index.html】"
    p.font.size = Pt(22)
    p.font.bold = True
    p.font.color.rgb = MANGO_ORANGE
    p.font.name = FONT_HEADING
    p.space_after = Pt(16)

    features = [
        ("三語介面動態切換 (越/印/中)", "解決台灣農場跨國溝通障礙，支援越南語與印尼語語音工單播報與圖解留蒂指南。"),
        ("即時氣候偵測 ╳ 梅雨避險轉運模擬", "點擊『模擬降雨』，系統立即演示將枋山芒果採收組，即時轉派至台南冷鏈包裝廠之避險路由。"),
        ("極簡 LINE 官方帳號原生整合", "小農免下載 App！56 歲阿龍班長在日常 LINE 介面即可 3 步驟完成叫工、排班與出工驗收確認。"),
        ("外銷留蒂 0.5cm 履歷數據驗收", "每日採收後自動生成品質驗收紀錄，數據直接銜接產銷履歷 (TAP) 稽核報表。")
    ]

    for title, desc in features:
        p_feat = tf.add_paragraph()
        p_feat.text = "✔ " + title + "："
        p_feat.font.bold = True
        p_feat.font.size = Pt(15)
        p_feat.font.color.rgb = ACCENT_GREEN
        p_feat.font.name = FONT_HEADING

        p_desc = tf.add_paragraph()
        p_desc.text = "    " + desc
        p_desc.font.size = Pt(13)
        p_desc.font.color.rgb = TEXT_LIGHT
        p_desc.font.name = FONT_BODY
        p_desc.space_after = Pt(10)

    add_notes(slide4, "這是我手寫出來的實際原型戰情室。各位請看螢幕：第一，針對移工，點擊這裡瞬間切換成越南語和印尼語，工單用語音播報，解決語言障礙；第二，調度大腦串接氣象資料，當我按下『模擬豪雨』，系統立即觸發轉運路由，把枋山果園工人派往台南冷鏈包裝廠；第三，農民端完全不需下載 App，直接在 LINE 官方帳號一鍵確認出工，驗收留蒂 0.5cm 外銷品質！")

    # ==========================================
    # SLIDE 5: MARKET OPPORTUNITY
    # ==========================================
    slide5 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide5, LIGHT_BG)
    add_header(slide5, "市場規模：從枋山灘頭堡到全台產曆調度（數據待補來源）")

    add_card(slide5, 0.8, 1.8, 3.6, 5.0, "第一環：灘頭堡市場", [
        "屏東枋山 ╳ 獅子鄉台 1 線愛文芒果外銷供果園。",
        "核心契作面積：約 300 公頃（待補來源）。",
        "灘頭堡勞務 GMV：約 4,500 萬元（待補來源）。",
        "首年目標：服務 12 戶契作大戶與冷鏈場（F2）。"
    ], border_color=PRIMARY_GREEN)

    add_card(slide5, 4.8, 1.8, 3.6, 5.0, "第二環：相鄰高經濟作物", [
        "依據全台產曆推移，移工跨產區連續流動：",
        "• 5~7月：屏東枋山 / 台南玉井芒果",
        "• 8~9月：台南麻豆文旦 (白露急採)",
        "• 10~11月：阿里山 / 竹山高山冬茶",
        "• 12~3月：屏東高樹蓮霧 ╳ 六龜蜜棗",
        "第二環市場規模：待補來源。"
    ], border_color=MANGO_ORANGE)

    add_card(slide5, 8.8, 1.8, 3.6, 5.0, "第三環：全台外展生態系", [
        "外展移工政策配額：待補來源。",
        "全台外展農務服務市場：待補來源。",
        "延伸市場：農機代耕 ╳ 採後冷鏈 ╳ 資材團購（規模待補來源）。",
        "TAM 估算方法與數據將於查證後補充。"
    ], border_color=DARK_BG)

    add_notes(slide5, "我們的擴張是一場產曆接力：從枋山愛文芒果出發，8 月麻豆文旦、10 月阿里山茶、12 月蓮霧蜜棗。透過產曆排程，第一年目標利用率 92%。市場規模數據我們正在查證來源，會在正式版本補上。")

    # ==========================================
    # SLIDE 6: BUSINESS MODEL & DEFENSE
    # ==========================================
    slide6 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide6, LIGHT_BG)
    add_header(slide6, "商業模式與防跳單機制：電信工時包 ╳ 雙向護城河")

    add_card(slide6, 0.8, 1.8, 5.6, 5.0, "多元獲利架構 (Revenue Streams)", [
        "早鳥主力工時包 (120H)：$37,800 ($315/hr，早鳥不打折)，產前預收 20% 定金，鎖定名額與確定性現金流。",
        "科技代管抽成 (Take Rate)：科技公司獨家向合作社收取 16% 軟體代管與調度服務費。",
        "產季 24H 緊急救火工時：單日急單 $2,880 ($360/hr)。",
        "第三層統包代耕：按分地/甲地計價，平台價差約 34%~36%。",
        "長尾增售：產銷履歷報表認證 ╳ 碎枝機代耕 ╳ 耗材團購。"
    ], title_color=PRIMARY_GREEN)

    add_card(slide6, 6.8, 1.8, 5.7, 5.0, "防跳單機制 (Anti-Disintermediation)", [
        "【小農端不敢跳單】：",
        "• 私下聘僱可能面臨就服法罰鍰（待確認）。",
        "• 失去「遲到免單、放鳥雙倍賠」之合約安全感。",
        "• 失去合法憑證與契約保障。",
        "",
        "【移工端不願跳單】：",
        "• 私下接工屬違法，可能影響在台工作資格。",
        "• 平台提供跨產區連續排班，收入較穩定。",
        "• 薪資不低於 2026 最低工資 29,500 元；8,800 元獎勵適用對象待確認。"
    ], title_color=ALERT_RED)

    add_notes(slide6, "我們採用『工時包』模式，產前 45 天由合作社預收 20% 定金，科技公司向合作社收取 16% 技術代管費。防跳單方面：小農私下聘僱有法律風險，也失去契約保障；移工在平台享有跨產區連續排程與合法收入。原本規劃的農業部 8,800 元獎勵，適用對象還在向農業部確認，所以我們不把它當作承諾。")

    # ==========================================
    # SLIDE 7: UNIT ECONOMICS
    # ==========================================
    slide7 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide7, LIGHT_BG)
    add_header(slide7, "財務預測（F2 v2.4）：科技公司與合作社兩端如實揭露")

    # 4 metric boxes
    metrics = [
        ("983.3 萬", "第一年平台 GMV", "12 名移工 ╳ 12 戶；基本情境採方案 C2（均價 $315、代管費 16%）", PRIMARY_GREEN),
        ("31.7 萬", "第一年科技公司 EBITDA", "不含補助；科技公司毛利率 87.9%", PRIMARY_GREEN),
        ("≈ 8 名", "科技公司損益平衡移工數", "每名移工年 1,800 計費小時\n合作社第一年兩平約 7 名", MANGO_ORANGE),
        ("+17.5 萬", "合作社第一年損益 (C2)", "損益率僅 2.3%，緩衝極薄\n利用率 −5 個百分點即虧 15.7 萬\nC2 定價尚待市場驗證", MANGO_ORANGE)
    ]

    for i, (val, label, sub, color) in enumerate(metrics):
        x = 0.8 + i * 2.95
        card = slide7.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(1.8), Inches(2.8), Inches(2.2))
        card.fill.solid()
        card.fill.fore_color.rgb = WHITE
        card.line.color.rgb = CARD_BORDER
        card.line.width = Pt(1.5)

        tb = slide7.shapes.add_textbox(Inches(x + 0.15), Inches(1.9), Inches(2.5), Inches(2.0))
        tf = tb.text_frame
        tf.word_wrap = True

        p = tf.paragraphs[0]
        p.text = val
        p.font.size = Pt(28)
        p.font.bold = True
        p.font.color.rgb = color
        p.font.name = FONT_HEADING
        p.space_after = Pt(4)

        p2 = tf.add_paragraph()
        p2.text = label
        p2.font.size = Pt(12)
        p2.font.bold = True
        p2.font.color.rgb = TEXT_MAIN
        p2.font.name = FONT_HEADING
        p2.space_after = Pt(4)

        p3 = tf.add_paragraph()
        p3.text = sub
        p3.font.size = Pt(10)
        p3.font.color.rgb = TEXT_MUTED
        p3.font.name = FONT_BODY

    # Financial table preview
    table_card = slide7.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(4.3), Inches(11.7), Inches(2.6))
    table_card.fill.solid()
    table_card.fill.fore_color.rgb = WHITE
    table_card.line.color.rgb = CARD_BORDER

    tb_t = slide7.shapes.add_textbox(Inches(1.1), Inches(4.5), Inches(11.1), Inches(2.2))
    tf_t = tb_t.text_frame
    tf_t.word_wrap = True

    p = tf_t.paragraphs[0]
    p.text = "三年財務營運預測 (Three-Year Projections)"
    p.font.size = Pt(16)
    p.font.bold = True
    p.font.color.rgb = PRIMARY_GREEN
    p.space_after = Pt(8)

    rows = [
        "• 第一年 (灘頭堡枋山)：12 位外展移工 | 12 戶 | GMV 983.3 萬 | 科技營收 183.6 萬 | 科技 EBITDA 31.7 萬 | 合作社 +17.5 萬",
        "• 第二年 (跨產區輪轉)：50 位外展移工 | 75 戶 | GMV 4,533.6 萬 | 科技營收 887.2 萬 | 科技 EBITDA 406.2 萬 | 合作社 +212.4 萬",
        "• 第三年 (全台平台化)：150 位外展移工 | 260 戶 | GMV 1.39 億 | 科技營收 2,769.7 萬 | 科技 EBITDA 1,534.7 萬 | 合作社 +1,073.6 萬",
        "• 以上為 C2 基本情境、不含補助、平台不承擔宿舍與專車；合併結果三年皆正（+49.2／+618.6／+2,608.3 萬）。\n  惟 C2 定價與人力配置尚待市場驗證，合作社第一年緩衝極薄（詳見財務報表 2 第捌、玖章）。"
    ]
    for r in rows:
        p_r = tf_t.add_paragraph()
        p_r.text = r
        p_r.font.size = Pt(12)
        p_r.font.color.rgb = TEXT_MAIN
        p_r.space_after = Pt(6)

    add_notes(slide7, "我們把財務模型依 2026 年最低工資與法定工時全部重算，基本情境採方案 C2：第一層均價每小時 315 元、早鳥不打折，代管費 16%，帶班師傅精簡並下田計費。第一年平台流水約 983 萬，科技公司營收約 184 萬、EBITDA 約 32 萬，約 8 名移工即可讓科技公司損益兩平。身為法定雇主的合作社第一年約有 17.5 萬結餘、約 7 名移工即可兩平，但損益率只有 2.3%，緩衝很薄；而且 C2 的定價與人力配置還沒有經過市場驗證，代管費率也要經合作社依章程決議。")

    # ==========================================
    # SLIDE 7b: 財務圖表（F2 v2.4，由 tools/make_charts.py 產生）
    # ==========================================
    charts_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                              "02_財務模型與政府補助", "charts")
    chart_cons = os.path.join(charts_dir, "f2_consolidated.png")
    chart_c1 = os.path.join(charts_dir, "f2_coop_c2.png")   # v2.3 基本情境、C1、v2.4 基本情境 C2（F2 v2.4）
    if os.path.exists(chart_cons) and os.path.exists(chart_c1):
        slide7b = prs.slides.add_slide(blank_slide_layout)
        set_bg(slide7b, LIGHT_BG)
        add_header(slide7b, "如實揭露：採 C2 後合作社三年皆有結餘，但第一年緩衝極薄", category="FINANCIALS")
        # 兩張圖左右並排（原圖 960×520，等比縮放）
        slide7b.shapes.add_picture(chart_cons, Inches(0.5), Inches(1.9), width=Inches(6.1))
        slide7b.shapes.add_picture(chart_c1, Inches(6.75), Inches(1.9), width=Inches(6.1))
        tb_c = slide7b.shapes.add_textbox(Inches(0.8), Inches(5.4), Inches(11.7), Inches(1.4))
        tf_c = tb_c.text_frame
        tf_c.word_wrap = True
        for line_text in [
            "• 科技公司、合作社與合併結果三年皆為正；但合作社第一年僅約 17.5 萬（損益率 2.3%），利用率降 5 個百分點即轉虧約 −15.7 萬，移工須提繳勞退 6% 則約 −8.0 萬。",
            "• C2＝第一層均價 $315（早鳥不打折）、師傅 1:10 下田計費＋移工組長、本土兼職 +50%、代管費率 16%、住宿預備金；\n定價與人力配置尚待市場驗證【待確認】，未通過即退回 C1 或 v2.3 數字。",
        ]:
            p_c = tf_c.add_paragraph() if tf_c.paragraphs[0].text else tf_c.paragraphs[0]
            p_c.text = line_text
            p_c.font.size = Pt(14)
            p_c.font.name = FONT_BODY
            p_c.font.color.rgb = TEXT_MAIN
            p_c.space_after = Pt(6)
        add_notes(slide7b, "左圖是三年的科技公司 EBITDA、合作社損益與合併結果：基本情境改採方案 C2 後，三者三年都是正的，科技公司的獲利不再建立在合作社虧損之上。右圖是對照：藍色是原本現行定價下合作社三年都虧損，橙色是替代方案 C1，綠色是現在的基本情境 C2，合作社第一年約 17.5 萬、第三年約 1,074 萬。但我們要誠實說明，第一年的緩衝很薄：利用率少 5 個百分點、或移工須提繳勞退，合作社第一年就會轉虧，所以第一年要用淡季保底工時合約守住利用率。C2 的定價與人力配置還需要市場驗證，代管費率 16% 也要經合作社依章程決議。")

    # ==========================================
    # SLIDE 8: DUAL-ENTITY MOAT
    # ==========================================
    slide8 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide8, LIGHT_BG)
    add_header(slide8, "雙軌架構：科技公司 ╳ 農業合作社")

    add_card(slide8, 0.8, 1.8, 5.6, 5.0, "智農科技股份有限公司 (Tech Co.)", [
        "【控股主體】：創辦團隊 100% 股權控股。",
        "【核心資產】：產曆調度演算法（專利申請規劃中）、軟體著作、三語介面系統、品牌商標。",
        "【收入機制】：與合作社簽訂《營運委託暨技術代管協議》，收取 16% 技術代管費（須經合作社依章程決議）。",
        "【資本運作】：未來融資、股權激勵、引進創投 (VC) 與 CVC 投資之純商業主體。"
    ], border_color=PRIMARY_GREEN, title_color=PRIMARY_GREEN)

    add_card(slide8, 6.8, 1.8, 5.7, 5.0, "有限責任農業勞務生產合作社 (Co-op)", [
        "【法定雇主】：向農業部申請外展農務服務資格，依法申請外展移工配額。",
        "【合規載體】：對要派果農開立合法憑證，社員一人一票依合作社法運作。",
        "【三項契約安排】：",
        "• 1. 營運委託協議 5 年，期滿無異議自動展延 3 年。",
        "• 2. 違約金 500 萬元條款（待律師審閱合理性）。",
        "• 3. 團隊成員依章程程序參選理事（利益衝突規範待確認）。"
    ], border_color=DARK_BG, title_color=DARK_BG)

    add_notes(slide8, "依現行規定，外展農務由合作社、農會等農民團體承辦。因此我們採『科技公司 ╳ 農業合作社』雙軌架構：合作社是法定雇主，科技公司提供調度系統與營運代管，雙方以 5 年期協議明定權利義務與 16% 代管費（須經合作社依章程決議）。相關法律安排都會請律師審閱。")

    # ==========================================
    # SLIDE 9: FOUNDER & CAPITAL EFFICIENCY
    # ==========================================
    slide9 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide9, DARK_BG)
    add_header(slide9, "極致資本效率：一個人、一部電腦、會寫程式的硬派創辦人", category="FOUNDER & EXECUTION", dark=True)

    box = slide9.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(1.8), Inches(11.7), Inches(5.0))
    box.fill.solid()
    box.fill.fore_color.rgb = RGBColor(30, 41, 59)
    box.line.color.rgb = PRIMARY_GREEN
    box.line.width = Pt(1.5)

    tb = slide9.shapes.add_textbox(Inches(1.2), Inches(2.1), Inches(10.9), Inches(4.4))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "【超高執行力 ╳ 極低燒錢率 (Extreme Burn Rate Discipline)】"
    p.font.size = Pt(20)
    p.font.bold = True
    p.font.color.rgb = MANGO_ORANGE
    p.font.name = FONT_HEADING
    p.space_after = Pt(14)

    pts = [
        ("零外包負擔，極速產品迭代", "獨立完成全套動態 Demo、三語工單介面、氣象避險演算法，代碼與產品手捏產出，功能修改以小時計算。"),
        ("輕資產營運", "不配置專車、不承租宿舍；住宿由特約農場／冷鏈場提供、交通由用工農戶安排，啟動資金需求 110 萬元（F2）。"),
        ("資金規劃", "資本額 10 萬元，搭配經濟部青年創業貸款 100 萬元（利息補貼年限待確認）；補助不列入基本財務規劃。"),
        ("參考 MIT 24 步架構", "商業計畫、財務模型與客戶分析參考《Disciplined Entrepreneurship》架構撰寫，關鍵假設（定價、利用率、引進時程）待田野驗證。")
    ]

    for t, d in pts:
        p_t = tf.add_paragraph()
        p_t.text = "★ " + t + "："
        p_t.font.bold = True
        p_t.font.size = Pt(15)
        p_t.font.color.rgb = ACCENT_GREEN
        p_t.font.name = FONT_HEADING

        p_d = tf.add_paragraph()
        p_d.text = "    " + d
        p_d.font.size = Pt(13)
        p_d.font.color.rgb = TEXT_LIGHT
        p_d.font.name = FONT_BODY
        p_d.space_after = Pt(10)

    add_notes(slide9, "各位評審，我目前是一個人、一部電腦、資本額 10 萬元的技術創辦人，獨立完成了各位眼前的原型與商業架構。我們不配置專車、不承租宿舍，以輕資產方式營運，啟動資金需求約 110 萬元。接下來最重要的是用田野數據驗證定價與利用率假設。")

    # ==========================================
    # SLIDE 10: THE ASK & VISION
    # ==========================================
    slide10 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide10, DARK_BG)
    add_header(slide10, "進駐訴求與願景：攜手 Garage+ 建立農業勞務數位基礎設施", category="THE ASK & VISION", dark=True)

    box = slide10.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(1.8), Inches(11.7), Inches(5.0))
    box.fill.solid()
    box.fill.fore_color.rgb = RGBColor(30, 41, 59)
    box.line.color.rgb = MANGO_ORANGE
    box.line.width = Pt(2)

    tb = slide10.shapes.add_textbox(Inches(1.2), Inches(2.1), Inches(10.9), Inches(4.4))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "【進駐 Garage+ 時代基金會三大核心訴求】"
    p.font.size = Pt(22)
    p.font.bold = True
    p.font.color.rgb = WHITE
    p.font.name = FONT_HEADING
    p.space_after = Pt(16)

    asks = [
        ("1. 企業 ESG 認購與通路 PoC 對接", "希望透過加速器之企業夥伴網絡（合作對象待確認），對接企業產地契作認購，以及連鎖通路之供應鏈勞動合規 PoC。"),
        ("2. 尋求商務拓展與農業運營共同創辦人 (Co-founder)", "借助 Garage+ 頂級創業者與校友社群，招募具備農業產銷、田野業務與供應鏈運營能力的互補合夥人加入。"),
        ("3. 雲端運算資源 (GCP / AWS Credits)", "申請跨國雲端平台之新創算力抵用金，以極低成本運行全台跨產曆動態調度大腦與氣候避險演算法。")
    ]

    for t, d in asks:
        p_t = tf.add_paragraph()
        p_t.text = "🎯 " + t
        p_t.font.bold = True
        p_t.font.size = Pt(15)
        p_t.font.color.rgb = ACCENT_GREEN
        p_t.font.name = FONT_HEADING

        p_d = tf.add_paragraph()
        p_d.text = "    " + d
        p_d.font.size = Pt(13)
        p_d.font.color.rgb = TEXT_LIGHT
        p_d.font.name = FONT_BODY
        p_d.space_after = Pt(12)

    p_vis = tf.add_paragraph()
    p_vis.text = "【終極願景】：成為台灣農業勞動力與 ESG 綠色供應鏈的第一數位基礎設施！"
    p_vis.font.size = Pt(16)
    p_vis.font.bold = True
    p_vis.font.color.rgb = MANGO_ORANGE
    p_vis.font.name = FONT_HEADING

    add_notes(slide10, "技術與產品我可以先扛，但我需要 Garage+ 的資源：對接企業 ESG 契作與通路，並找到懂農業產銷的商務合夥人。希望在加速器期間完成定價驗證與第一期營運準備。謝謝大家！")

    # Output file
    output_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "02_智農派工_Demo簡報_加速器Pitch.pptx")
    polish_cjk(prs)
    prs.save(output_path)
    print(f"Successfully generated: {output_path}")

if __name__ == "__main__":
    create_presentation()
