package com.agridispatch.service;

import com.agridispatch.entity.Talent;
import com.agridispatch.repository.TalentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TalentService {
    private final TalentRepository talentRepository;

    public TalentService(TalentRepository talentRepository) {
        this.talentRepository = talentRepository;
    }

    public List<Talent> getAllTalents() {
        return talentRepository.findAll();
    }

    public Optional<Talent> getTalentById(String id) {
        return talentRepository.findById(id);
    }

    public Talent createTalent(Talent talent) {
        if (talent.getId() == null || talent.getId().trim().isEmpty()) {
            talent.setId("TAL-" + String.format("%03d", talentRepository.count() + 1));
        }
        if (talent.getRegisteredAt() == null) {
            talent.setRegisteredAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        }
        return talentRepository.save(talent);
    }

    public Talent updateTalent(String id, Talent updated) {
        return talentRepository.findById(id).map(existing -> {
            existing.setName(updated.getName());
            existing.setCategory(updated.getCategory());
            existing.setCategoryName(updated.getCategoryName());
            existing.setPhone(updated.getPhone());
            existing.setExperience(updated.getExperience());
            existing.setPdpaConsent(updated.isPdpaConsent());
            return talentRepository.save(existing);
        }).orElseThrow(() -> new RuntimeException("Talent not found: " + id));
    }

    public void deleteTalent(String id) {
        talentRepository.deleteById(id);
    }
}
