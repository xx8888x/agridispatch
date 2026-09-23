import sys
import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE

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

    def add_header(slide, title_text, category="智農派工 AgriDispatch"):
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
        p_title.font.color.rgb = TEXT_MAIN
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
            p.text = "• " + item
            p.font.size = Pt(13)
            p.font.color.rgb = TEXT_MAIN
            p.font.name = FONT_BODY
            p.space_after = Pt(8)

        return card

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
    p2.text = "台灣首創合法外展農務動態調度大腦與避險平台"
    p2.font.size = Pt(22)
    p2.font.color.rgb = ACCENT_GREEN
    p2.font.name = FONT_HEADING
    p2.space_after = Pt(24)

    p3 = tf.add_paragraph()
    p3.text = "AI 動態調度  |  特許雙軌合作社  |  農業 ESG 供應鏈閉環"
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

    add_notes(slide1, "各位評審好，我是創辦人。台灣農業正面臨 64 歲老齡化斷崖缺工，以及百億非法黑工的雙重危機。今天我帶來的『智農派工』，不是一個概念，而是已經完成原型開發、用演算法與特許雙軌機制，為台灣農業打造的動態調度大腦！")

    # ==========================================
    # SLIDE 2: THE PROBLEM
    # ==========================================
    slide2 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide2, LIGHT_BG)
    add_header(slide2, "台灣農業的致命死穴：老齡化斷崖 ╳ 百億非法黑工")

    add_card(slide2, 0.8, 1.8, 3.6, 5.0, "痛點 1：產季大放鳥", [
        "果農平均年齡高達 64.4 歲，產季採收已達生理極限。",
        "地下黑工無合約約束，每工多開 $200 當天直接被截胡。",
        "外銷愛文芒果熟透不及採收，2 天內軟爛落果。",
        "單季放鳥損失動輒高達 20 萬 ~ 50 萬元！"
    ], border_color=ALERT_RED, title_color=ALERT_RED)

    add_card(slide2, 4.8, 1.8, 3.6, 5.0, "痛點 2：梅雨停工損失", [
        "5~6 月採收大尖峰適逢台灣連日梅雨豪雨。",
        "果園泥濘無法採摘，移工在宿舍白吃閒置工資。",
        "缺乏跨區與上下游銜接機制，看天吃飯無從避險。",
        "放晴後工人常已被黑工頭轉派其他工地，求助無門。"
    ], border_color=MANGO_ORANGE, title_color=MANGO_ORANGE)

    add_card(slide2, 8.8, 1.8, 3.6, 5.0, "痛點 3：法規重罰與撤櫃", [
        "聘僱非法黑工面臨勞動部 15~75 萬元巨額罰鍰。",
        "全聯、好市多與日韓外銷通路要求 100% 合法發票。",
        "現金黑市無進項憑證，抽查違法即整批退貨撤櫃。",
        "契作大戶隨時面臨身敗名裂與取消資格風險。"
    ], border_color=PRIMARY_GREEN, title_color=PRIMARY_GREEN)

    add_notes(slide2, "屏東枋山 56 歲的愛文芒果產銷班長阿龍，每年產季都在失眠。為什麼？因為傳統地下黑工毫無合約約束，別人每工多開 200 元當天就被截胡！芒果 2 天沒採完，熟透落果，一季損失 25 萬。更可怕的是，非法黑工隨時面臨 75 萬重罰，一旦被通路抽查，外銷契作資格立刻泡湯。")

    # ==========================================
    # SLIDE 3: THE SOLUTION
    # ==========================================
    slide3 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide3, LIGHT_BG)
    add_header(slide3, "智農派工破局方案：不賣人頭，賣「確定性與外銷品質」")

    add_card(slide3, 0.8, 1.8, 2.7, 5.0, "1. 100% 保證出工", [
        "產季早鳥保證工時包 (120H)。",
        "白紙黑字法律承諾：遲到免單、放鳥雙倍賠償現金！",
        "產前 45 天收定 20%，鎖定名額消除心理焦慮。"
    ])

    add_card(slide3, 3.8, 1.8, 2.7, 5.0, "2. 外銷留蒂 SOP", [
        "首創越/印/中三語圖解語音採收培訓指南。",
        "標準化「留蒂 0.5cm 護果粉」技術，免除乳膠傷果。",
        "日韓外銷 A 級品合格率提升至 92% 以上！"
    ])

    add_card(slide3, 6.8, 1.8, 2.7, 5.0, "3. 梅雨冷鏈避險", [
        "串接中央氣象署降雨雷達回波 API。",
        "遇果園豪雨停工，演算法自動轉運移工進冷鏈廠分級。",
        "工資由包裝廠分攤，農民零停工損失。"
    ])

    add_card(slide3, 9.8, 1.8, 2.7, 5.0, "4. 100% 合規發票", [
        "有限責任外展合作社合法開立農事勞務發票。",
        "全額扣抵產銷班營業稅與所得稅憑證。",
        "提供好市多、全聯 ESG 勞動審查免死金牌。"
    ])

    add_notes(slide3, "智農派工不賣人頭，我們賣的是『確定性與外銷品質』！我們向農民預售『早鳥保證工時包』，敢在合約寫上：遲到免單、放鳥賠償雙倍工資！遇上連日大雨，系統自動轉運工人進室內冷鏈廠分級，小農不虧工資、工人不停工，更開立 100% 合法發票，成為果農在通路的免死金牌！")

    # ==========================================
    # SLIDE 4: LIVE PRODUCT DEMO
    # ==========================================
    slide4 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide4, DARK_BG)
    add_header(slide4, "現場原型展示：手捏可運行的數位戰情室 (Live Demo)", category="PRODUCT & TECHNOLOGY")

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
    p.text = "【請切換筆電螢幕至展示系統：Demo/app.html】"
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
    add_header(slide5, "市場規模：從枋山灘頭堡到全台 170 億產曆調度大腦")

    add_card(slide5, 0.8, 1.8, 3.6, 5.0, "第一環：灘頭堡市場", [
        "屏東枋山 ╳ 獅子鄉台 1 線愛文芒果外銷供果園。",
        "核心契作面積：300 公頃 (18,000 採收工日)。",
        "灘頭堡勞務 GMV：4,500 萬元台幣。",
        "目標首年獲取 10 戶代表性班長客戶，驗證財務閉環。"
    ], border_color=PRIMARY_GREEN)

    add_card(slide5, 4.8, 1.8, 3.6, 5.0, "第二環：相鄰高經濟作物", [
        "依據全台產曆推移，移工跨產區連續流動：",
        "• 5~7月：屏東枋山 / 台南玉井芒果",
        "• 8~9月：台南麻豆文旦 (白露急採)",
        "• 10~11月：阿里山 / 竹山高山冬茶",
        "• 12~3月：屏東高樹蓮霧 ╳ 六龜蜜棗",
        "第二環市場規模：31.2 億元 GMV！"
    ], border_color=MANGO_ORANGE)

    add_card(slide5, 8.8, 1.8, 3.6, 5.0, "第三環：全台外展生態系", [
        "政策上限：農業部 12,000 ~ 20,000 外展移工配額。",
        "全台法定外展農工派遣基本盤：100 億元 GMV。",
        "增售延伸：農機代耕 (20億) ╳ 採後冷鏈 (30億) ╳ 資材團購 (20億)。",
        "廣義市場總天花板 (TAM)：170 億元新台幣！"
    ], border_color=DARK_BG)

    add_notes(slide5, "我們的擴張是一場精心計算的產曆接力賽：從枋山愛文芒果出發，8月麻豆文旦、10月阿里山茶、12月蓮霧蜜棗。透過產曆調度大腦，移工全年在工率達到 92%。從 4,500 萬的灘頭堡，一路解鎖至全台灣 170 億的外展農務與供應鏈市場！")

    # ==========================================
    # SLIDE 6: BUSINESS MODEL & DEFENSE
    # ==========================================
    slide6 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide6, LIGHT_BG)
    add_header(slide6, "商業模式與防跳單機制：電信工時包 ╳ 雙向護城河")

    add_card(slide6, 0.8, 1.8, 5.6, 5.0, "多元獲利架構 (Revenue Streams)", [
        "早鳥主力工時包 (120H)：早鳥價 $33,600 ($280/hr)，產前預收 20% 定金，鎖定確定性現金流。",
        "科技代管抽成 (Take Rate)：科技公司獨家向合作社收取 18% 軟體代管與調度服務費。",
        "產季 24H 緊急救火工時：單日急單 $2,880 ($360/hr)，毛利率突破 30% 以上。",
        "梅雨冷鏈避險手續費：連日大雨轉運集貨廠，消除小農損失並賺取避險服務費。",
        "長尾增售：產銷履歷報表認證 ╳ 碎枝機代耕 ╳ 耗材團購。"
    ], title_color=PRIMARY_GREEN)

    add_card(slide6, 6.8, 1.8, 5.7, 5.0, "無懈可擊的防跳單機制 (Anti-Disintermediation)", [
        "【小農端不敢跳單】：",
        "• 私下留電話私聘，面臨勞動部最高 75 萬重罰。",
        "• 失去「遲到免單、放鳥雙倍賠」之合約安全感。",
        "• 失去全聯、好市多契作必需之 100% 營業發票憑證。",
        "",
        "【移工端不願跳單】：",
        "• 私接黑工一旦被查獲立即遣返，喪失在台合法資格。",
        "• 私接只能賺 3 天，在平台享有全年 300 天連續跨區排班。",
        "• 享有農業部每月 8,800 元就業獎勵，實領月薪逼近 4 萬元！"
    ], title_color=ALERT_RED)

    add_notes(slide6, "我們採用『電信月租工時包』模式，產前 45 天預收 20% 定金，科技公司抽取 18% 技術代管費。最關鍵的是防跳單機制：小農私下聘僱面臨 75 萬罰鍰與退櫃；而移工阿雄在我們平台享有全年連續排程，加計中央 8,800 元獎勵月薪破 4 萬，移工主動拒絕私接黑工，建立極高防禦護城河！")

    # ==========================================
    # SLIDE 7: UNIT ECONOMICS
    # ==========================================
    slide7 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide7, LIGHT_BG)
    add_header(slide7, "單位經濟與財務健全度：驗證可行的獲利引擎")

    # 4 metric boxes
    metrics = [
        ("$27,719", "5 年客戶終身價值 (LTV)", "基於 85% 留存率與 20% 資金折現率精算之淨毛利", PRIMARY_GREEN),
        ("$7,100", "第一年獲客成本 (COCA)", "由上而下全成本核算 (第二年降至 $2,400)", ALERT_RED),
        ("3.9x ➔ 11.5x", "LTV / COCA 比率", "遠超創投健康標準 3.0 倍，投資回報極高", MANGO_ORANGE),
        ("< 4 個月", "獲客成本回本期", "第一年產季結束即 100% 全額收回獲客成本", PRIMARY_GREEN)
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
        "• 第一年 (灘頭堡枋山)：管理 12 位外展移工 | 10 戶契作農戶 | GMV 300 萬 | 科技營收 54 萬 | EBITDA $12 萬 (首年獲利轉正！)",
        "• 第二年 (跨產區輪轉)：管理 50 位外展移工 | 60 戶農戶 (芒果+文旦+茶) | GMV 1,680 萬 | 科技營收 319 萬 | EBITDA $145 萬元",
        "• 第三年 (全台平台化)：管理 150 位外展移工 | 220 戶農戶 | GMV 5,200 萬 | 科技營收 1,040 萬 | EBITDA $580 萬元"
    ]
    for r in rows:
        p_r = tf_t.add_paragraph()
        p_r.text = r
        p_r.font.size = Pt(12)
        p_r.font.color.rgb = TEXT_MAIN
        p_r.space_after = Pt(6)

    add_notes(slide7, "這不是一門燒錢講故事的生意。依據 MIT 創業法的嚴謹折現，阿龍班長 5 年為我們貢獻 2.7 萬毛利，而我們的獲客成本只有 7,100 元，比率達 3.9 倍，而且不到 4 個月全額回本！第一年我們就能實現正向現金流與獲利！")

    # ==========================================
    # SLIDE 8: DUAL-ENTITY MOAT
    # ==========================================
    slide8 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide8, LIGHT_BG)
    add_header(slide8, "法律與特許控制權護城河：雙軌架構消滅政策風險")

    add_card(slide8, 0.8, 1.8, 5.6, 5.0, "智農派工科技股份有限公司 (Tech Co.)", [
        "【控股主體】：創辦團隊 100% 股權控股。",
        "【核心資產】：全台產曆調度大腦演算法專利、軟體智財權、三語介面系統、品牌商標。",
        "【獲利機制】：獨家與合作社簽訂《營運委託暨技術代管協議》，向合作社收取 18% 技術代管與維運費。",
        "【資本運作】：未來融資、股權激勵、引進創投 (VC) 與 CVC 投資之純商業主體。"
    ], border_color=PRIMARY_GREEN, title_color=PRIMARY_GREEN)

    add_card(slide8, 6.8, 1.8, 5.7, 5.0, "有限責任農業勞務生產合作社 (Co-op)", [
        "【特許主體】：依法向農業部申請「外展農務機構特許資格」，合法申請外展移工配額。",
        "【合規載體】：對要派果農開立 100% 合規發票，社員一人一票符合合作社法規範。",
        "【三大控制權防護鎖】：",
        "• 1. 獨家簽訂 10 年排他營運委託協議，禁止私自調度。",
        "• 2. 明定 500 萬元懲罰性違約金條款，防社員奪權。",
        "• 3. 科技公司團隊兼任理事長與總幹事，牢控公章與名額。"
    ], border_color=DARK_BG, title_color=DARK_BG)

    add_notes(slide8, "在法規上，外展移工只開放給合作社與農會。我們設計了『科技公司 ╳ 特許合作社』雙軌架構：以 10 年排他代管協議與 500 萬違約條款，確保科技公司的絕對控制權與 18% 利潤，同時合法享有政府外展特許身分，杜絕政策風險。")

    # ==========================================
    # SLIDE 9: FOUNDER & CAPITAL EFFICIENCY
    # ==========================================
    slide9 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide9, DARK_BG)
    add_header(slide9, "極致資本效率：一個人、一部電腦、會寫程式的硬派創辦人", category="FOUNDER & EXECUTION")

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
        ("徹底剔除重資產陷阱", "不花數百萬自購車隊與自建宿舍；採用「在地租賃車按趟計費 ╳ 現成農舍月租」之純變動成本模式，啟動資金壓至 60 萬以內。"),
        ("資本運用槓桿化", "資本額 10 萬自有資金，搭配進駐後申請經濟部青年創業貸款 100 萬（5 年免息補貼），放大 10 倍營運槓桿。"),
        ("MIT 24 步嚴密驗證", "全套商業計畫書、LTV/COCA 財務模型、DMU 客戶心理攻防皆經由 MIT 體系推演閉環，拒絕任何自嗨與假需求。")
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

    add_notes(slide9, "各位評審，我目前是一個人、一部電腦、資本額 10 萬的技術創辦人。但我沒有花任何一毛外包費，憑藉極致自驅力，手捏出各位眼前所有的代碼、原型與商業架構。我徹底排除了重資產陷阱，採用純變動成本運作。我的極低燒錢率與手捏產品能力，就是這家公司最強大的生存發動機！")

    # ==========================================
    # SLIDE 10: THE ASK & VISION
    # ==========================================
    slide10 = prs.slides.add_slide(blank_slide_layout)
    set_bg(slide10, DARK_BG)
    add_header(slide10, "進駐訴求與願景：攜手 Garage+ 撬動百億農業新基建", category="THE ASK & VISION")

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
        ("1. 企業 ESG 認購與通路 PoC 對接", "時代基金會背後有台灣頂尖科技與金融巨頭（台積電、富邦、國泰、緯創等）。我們希望對接大企業福委會產地契作認購，以及連鎖通路之供應鏈勞動合規 PoC。"),
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

    add_notes(slide10, "技術與產品我一個人能扛，但我需要 Garage+ 的槓桿！時代基金會背後有台灣最強的科技巨頭與通路資源，我希望在此對接企業 ESG 契作通路，並尋找優秀的農業商務合夥人加入。給我一個工位與這座生態系的槓桿，我會把這個百億農業勞力缺口徹底打穿！謝謝大家！")

    # Output file
    output_path = os.path.join(r"d:\派遣專案網站(農事及其它)", "智農派工_Demo簡報_加速器Pitch.pptx")
    prs.save(output_path)
    print(f"Successfully generated: {output_path}")

if __name__ == "__main__":
    create_presentation()
