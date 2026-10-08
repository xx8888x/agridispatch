package com.agridispatch.service;

import com.agridispatch.entity.Talent;
import com.agridispatch.repository.TalentRepository;
import com.agridispatch.common.ApiException;
import com.agridispatch.common.IdGenerator;
import com.agridispatch.common.PartialUpdate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class TalentService {
    private final TalentRepository talentRepository;
    private final Object idLock = new Object();

    public TalentService(TalentRepository talentRepository) {
        this.talentRepository = talentRepository;
    }

    public List<Talent> getAllTalents() {
        return talentRepository.findAll();
    }

    public Optional<Talent> getTalentById(String id) {
        return talentRepository.findById(id);
    }

    /**
     * 登錄人才：ID 一律由後端產生（取現有最大序號 +1 並檢查存在），不會覆寫既有資料
     * @param requireConsent 公開報名端點必須勾選個資同意
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Talent createTalent(Talent talent, boolean requireConsent) {
        if (talent.getName() == null || talent.getName().isBlank()) {
            throw ApiException.badRequest("姓名 (name) 為必填欄位");
        }
        if (talent.getName().length() > 100) {
            throw ApiException.badRequest("姓名過長");
        }
        if (talent.getPhone() != null && talent.getPhone().length() > 30) {
            throw ApiException.badRequest("聯絡電話過長");
        }
        if (requireConsent) {
            if (talent.getPhone() == null || talent.getPhone().isBlank()) {
                throw ApiException.badRequest("聯絡電話 (phone) 為必填欄位");
            }
            if (!talent.isPdpaConsent()) {
                throw ApiException.badRequest("請先同意個人資料蒐集與利用 (pdpaConsent)");
            }
        }
        // 資料庫欄位：H2 預設 varchar(255)、Oracle VARCHAR2(1000 BYTE)（中文約 333 字），取 255 字
        if (talent.getExperience() != null && talent.getExperience().length() > 255) {
            throw ApiException.badRequest("經歷描述過長（上限 255 字）");
        }
        talent.setRegisteredAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        synchronized (idLock) {
            talent.setId(IdGenerator.next("TAL-", 3, talentRepository.findIdsByPrefix("TAL-"), talentRepository::existsById));
            return talentRepository.saveAndFlush(talent);
        }
    }

    /** 部分更新：只改 body 中有帶且非 null 的欄位（id、登錄時間不可改）；姓名明確傳空字串回 400 */
    public Talent updateTalent(String id, Map<String, Object> body) {
        Talent existing = talentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("找不到該人才編號: " + id));
        PartialUpdate.rejectBlank(body, "name", "姓名");
        PartialUpdate.apply(existing, body, Set.of("id", "registeredAt"));
        if (existing.getName().length() > 100) throw ApiException.badRequest("姓名過長");
        if (existing.getPhone() != null && existing.getPhone().length() > 30) throw ApiException.badRequest("聯絡電話過長");
        if (existing.getExperience() != null && existing.getExperience().length() > 255) {
            throw ApiException.badRequest("經歷描述過長（上限 255 字）");
        }
        return talentRepository.save(existing);
    }

    public void deleteTalent(String id) {
        if (!talentRepository.existsById(id)) {
            throw ApiException.notFound("找不到該人才編號: " + id);
        }
        talentRepository.deleteById(id);
    }
}
