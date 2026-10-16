package com.agridispatch.service;

import com.agridispatch.common.ApiException;
import com.agridispatch.common.PartialUpdate;
import com.agridispatch.entity.Brochure;
import com.agridispatch.repository.BrochureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class BrochureService {

    private final BrochureRepository brochureRepository;

    public BrochureService(BrochureRepository brochureRepository) {
        this.brochureRepository = brochureRepository;
    }

    public List<Brochure> getAllBrochures(boolean activeOnly) {
        if (activeOnly) {
            return brochureRepository.findByStatus("active");
        }
        return brochureRepository.findAll();
    }

    public Optional<Brochure> getBrochureById(String id) {
        return brochureRepository.findById(id);
    }

    public Brochure saveBrochure(Brochure brochure) {
        if (brochure.getId() == null || brochure.getId().trim().isEmpty()) {
            String id;
            do {
                id = "dm-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 4);
            } while (brochureRepository.existsById(id));
            brochure.setId(id);
        }
        if (brochure.getDepositAmount() == 0 && brochure.getDiscountPrice() > 0) {
            int rate = brochure.getDepositRate() > 0 ? brochure.getDepositRate() : 20;
            brochure.setDepositAmount(Math.round(brochure.getDiscountPrice() * rate / 100.0f));
        }
        if (brochure.getStatus() == null || brochure.getStatus().trim().isEmpty()) {
            brochure.setStatus("active");
        }
        return brochureRepository.save(brochure);
    }

    /** 部分更新：只改 body 中有帶且非 null 的欄位（id 不可改）；標題明確傳空字串回 400 */
    public Brochure updateBrochure(String id, Map<String, Object> body) {
        Brochure existing = brochureRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("找不到該產品文宣 DM: " + id));
        PartialUpdate.rejectBlank(body, "title", "文宣標題");
        PartialUpdate.apply(existing, body, Set.of("id"));
        return saveBrochure(existing);
    }

    public void deleteBrochure(String id) {
        brochureRepository.deleteById(id);
    }

    /**
     * 重置 4 大標準文宣 DM：依固定 ID 覆寫為目前範本內容（管理後台「重置 4 大標準 DM」）。
     * 只覆寫這 4 筆，使用者自訂的其他 DM 不受影響。首次啟動的種子資料由 SeedService 負責。
     */
    public void seedDefaultBrochures() {

        // DM 1: 枋山愛文芒果產季早鳥保證出工（315 元/hr 不打折）
        Brochure dm1 = new Brochure(
            "dm-mango-2026",
            "【屏東枋山外銷愛文芒果】產季早鳥保證出工 60 工時包",
            "告別產季搶工被放鳥！合法外展工班 ╳ 產季保證出工 ╳ 專車準時接送",
            "採收早鳥",
            "愛文芒果",
            "屏東枋山、台南玉井",
            "外銷供果園、全聯契作戶、外銷貿易商契作園",
            "★ 產銷班長指名推薦款",
            "🥭",
            "emerald",
            "https://images.unsplash.com/photo-1553279768-865429fa0078?w=800&auto=format&fit=crop&q=80",
            18900,
            18900,
            20,
            3780,
            60,
            4,
            2,
            "內含 60 個標準外展技術工時（約 4 位熟練農工出勤 2 天）。產季前 60 天預購，每小時 315 元不打折；預付 20% 定金即可鎖定產季名額、優先排班，遲到免單、放鳥雙倍賠！",
            "[{\"icon\":\"🛡️\",\"title\":\"保證 1：保證出工，違約雙倍賠償\",\"desc\":\"合約簽訂後，約定出工日清晨 07:00 前專車準時送抵田門口。因平台失誤導致延遲，當日工資全免並加倍賠償農損！\"},{\"icon\":\"✂️\",\"title\":\"保證 2：外銷採收 SOP，保護果粉\",\"desc\":\"全程配戴細絨棉手套，嚴禁手指摩擦果粉。蒂頭精準保留 0.5～1.0 公分，輕採輕放，特級品良率高達 95% 以上！\"},{\"icon\":\"🧾\",\"title\":\"保證 3：合法發票與勞保名冊，安心報帳\",\"desc\":\"完工即開立合作社統一發票並附上全員職災保險名冊，全聯、好市多與國稅局稽核 100% 合規，經營高枕無憂！\"}]",
            "[{\"item\":\"法律合規性\",\"illegal\":\"違法非法黑工，被查獲罰 15～75 萬元\",\"legal\":\"依外展農務制度申請中，依法足額納保！\"},{\"item\":\"出工確定性\",\"illegal\":\"口頭約定，隨時被隔壁大戶加價 300 截工放鳥\",\"legal\":\"簽署正式契約，白紙黑字保證出工，違約雙倍賠償！\"},{\"item\":\"採收手路技術\",\"illegal\":\"烏合之眾，碰掉外銷果粉、剪傷蒂頭變次級品\",\"legal\":\"受過外銷日韓採收 SOP 訓練，蒂頭留 0.5cm，果粉完好！\"},{\"item\":\"發票與稽核\",\"illegal\":\"只能開手寫白單，全聯、好市多無法報帳抵稅\",\"legal\":\"開立合法合作社統一發票，全聯通路稽核 100% 過關！\"}]",
            "陳建龍（阿龍班長）",
            "枋山愛文芒果產銷班第 7 班 班長",
            "【預期效益示意，非實際客戶見證】「去年被工頭放鳥，爛了快 30 萬芒果，我差點跟老婆翻臉。今年我第一個跟智農派工簽早鳥合約，預付定金。5 月 20 號那天早上 6 點 50 分，4 個越南年輕人準時站在我田門口，剪蒂頭比我自己剪的還幼路！明年產季，我們全班都要一起簽早鳥包！」",
            "08-876-1234 / 0912-345-678",
            "屏東縣枋山鄉加祿路 128 號 (加祿保安宮旁)",
            "active",
            "2026-09-01"
        );

        // DM 2: 桃園八德/大溪設施溫室有機蔬菜
        Brochure dm2 = new Brochure(
            "dm-greenhouse-2026",
            "【桃園/雲林設施有機溫室】全年常態巡檢與蔬果採收包裝 120 工時包",
            "水耕與有機溫室首選！365天穩定固定工班 ╳ 定植採收分級一貫化 ╳ 供應營養午餐零斷線",
            "設施溫室",
            "有機溫室蔬果",
            "桃園八德、大溪、雲林西螺",
            "大台北有機蔬菜供銷班、全聯有機水耕專區、國中小營養午餐供應商",
            "★ 全年常態合作滿意度 99.8%",
            "🥬",
            "lime",
            "https://images.unsplash.com/photo-1585320806297-9794b3e4eeae?w=800&auto=format&fit=crop&q=80",
            37800,
            37800,
            20,
            7560,
            120,
            6,
            4,
            "專為溫室有機蔬菜農場量身打造的月約工時包！提供受過有機驗證衛生訓練之常態班底，含溫室穴盤定植、除草整枝與精緻採收。",
            "[{\"icon\":\"🌱\",\"title\":\"保證 1：固定班底，免天天重新培訓\",\"desc\":\"指派專屬溫室常駐工班，熟悉農場作業手路，降低作物折損率至 1% 以下！\"},{\"icon\":\"🧤\",\"title\":\"保證 2：符合有機農產品驗證衛生標準\",\"desc\":\"配戴專用防護鞋套、帽子與衛生手套，作業前後嚴格工具消毒，100% 符規。\"},{\"icon\":\"🧾\",\"title\":\"保證 3：提供合法月結發票與企業扣抵憑證\",\"desc\":\"每月定期彙整工時簽核單，開立營業稅進項憑證，直通大型通路會計系統。\"}]",
            "[{\"item\":\"人員穩定度\",\"illegal\":\"臨時找散工，每天換新面孔，整畦菜被拔爛\",\"legal\":\"固定簽約工班，長期配合，熟悉產銷班作業標準！\"},{\"item\":\"衛生規範\",\"illegal\":\"無衛生防護概念，抽菸檳榔進溫室恐遭吊銷有機標章\",\"legal\":\"嚴格遵循有機栽培規範，專用防護與工具定期消毒！\"},{\"item\":\"通路供貨\",\"illegal\":\"因缺工延遲出貨被量販店罰款甚至斷單\",\"legal\":\"保證出勤合約，供應大台北全聯與學校午餐零脫班！\"},{\"item\":\"會計核銷\",\"illegal\":\"私人工頭無發票，農業公司無法列報生產費用\",\"legal\":\"合法合作社進項憑證，完整扣抵 5% 營業稅！\"}]",
            "廖國安（阿安班長）",
            "桃園八德有機蔬菜產銷班第 3 班 班長",
            "【預期效益示意，非實際客戶見證】「我們天天都要交 3 萬把有機青江菜到全聯，只要一天工人沒來我就準備賠錢！自從跟智農派工簽了 120 工時常態包，工班每天 7 點準時到溫室報到。工人手腳乾淨又快，挑菜切菜比老手還俐落。現在我們整個八德有機產銷班都安心交給智農！」",
            "03-368-2111 / 0932-111-222",
            "桃園市八德區興豐路 827 號 (八德區農會推廣部對面)",
            "active",
            "2026-09-02"
        );

        // DM 3: 嘉南平原大面積契作農田 T50 無人機植保與機耕深耕
        Brochure dm3 = new Brochure(
            "dm-drone-2026",
            "【嘉南平原大面積契作農田】大疆 T50 大型無人機植保與 80hp 機耕深耕包",
            "科技農業正規軍！RTK 公分級高精度導航 ╳ 雙離心大流量噴霧 ╳ 附完整飛行軌跡產銷履歷",
            "智慧機耕",
            "水稻、雜糧、硬質玉米",
            "雲林元長、嘉義太保、台南後壁",
            "大面積契作大農、育苗中心、產銷班、代耕代噴需求農戶",
            "★ 科技代噴良率達標保證",
            "🛸",
            "indigo",
            "https://images.unsplash.com/photo-1527061011665-3652c757a4d4?w=800&auto=format&fit=crop&q=80",
            28800,
            25200,
            20,
            5040,
            30,
            2,
            1,
            "採用 DJI Agras T50 大型農業植保無人機（40 公斤播撒／50 公升噴霧）搭配久保田 80hp 曳引機。雙飛手持有民航局與農業部雙專業證照，完工提供精準飛行圖資與防漂移履歷！",
            "[{\"icon\":\"🛸\",\"title\":\"保證 1：雙證照合規飛手，投保 5,000 萬責任險\",\"desc\":\"全體飛手持農業藥劑代噴證照與民航局 G2 高級專業操作證，合法合規施作。\"},{\"icon\":\"🛰️\",\"title\":\"保證 2：RTK 公分級航跡定位，零漏噴零重疊\",\"desc\":\"架設高精度地面差分基站，航線精度達 2.5cm，均勻附著作物葉背。\"},{\"icon\":\"🚜\",\"title\":\"保證 3：深耕整地與高壓噴藥一貫化統包作業\",\"desc\":\"80 匹馬力迴轉式碎土深耕達 30 公分，打破犁底層，提升根系抗倒伏力。\"},{\"icon\":\"📊\",\"title\":\"保證 4：施作後 24 小時出具數位圖資防驗收爭議\",\"desc\":\"無人機飛行軌跡、用藥量、施作面積與天候感測數據即時匯整為數位報告。\"}]",
            "[{\"item\":\"飛安合法性\",\"illegal\":\"黑飛黑噴無證照，違法遭民航局與防檢署重罰 30 萬\",\"legal\":\"合法民航局活動許可，雙飛手受訓納保，合法無慮！\"},{\"item\":\"噴灑精準度\",\"illegal\":\"目視手飛，常漏噴斑塊導致稻熱病擴散或藥害\",\"legal\":\"全自動 RTK 航線規畫，微滴超音波霧化，穿透力強！\"},{\"item\":\"周邊鄰田爭議\",\"illegal\":\"風大盲目噴灑，農藥飄移隔壁有機田被求償百萬\",\"legal\":\"配備微氣象超音波監測，陣風超標自動熔斷警報！\"},{\"item\":\"設備可靠度\",\"illegal\":\"舊型雜牌機故障摔機，延誤產季防治黃金 48 小時\",\"legal\":\"全新大疆 T50 雙機輪替，備用發電機與水車隨隊馳援！\"},{\"item\":\"產銷履歷認可\",\"illegal\":\"無法出具合法證明，米廠與糧商無法認可\",\"legal\":\"產出合規履歷圖資，糧商契作驗收直接快速過關！\"}]",
            "許世峰（峰哥隊長）",
            "元長雜糧機械化代耕隊 隊長",
            "【預期效益示意，非實際客戶見證】「我們元長種黑豆、水稻面積幾十甲，人工噴藥根本找不到人，工錢喊到一天 3500 還請不到人！智農的無人機隊早上 5 點來，2 個鐘頭直接把 15 甲地噴得整整齊齊，藥水全部均勻附著在葉子背面。還有一台 80 匹馬力的曳引機幫忙深耕，省下我至少 4 天工！這才是現代農業的樣子！」",
            "05-788-5678 / 0918-668-999",
            "雲林縣元長鄉中山路 66 號 (農會特作推廣中心旁)",
            "active",
            "2026-09-03"
        );

        brochureRepository.saveAll(List.of(dm1, dm2, dm3));
    }
}
