package com.agridispatch.service;

import com.agridispatch.entity.Crop;
import com.agridispatch.repository.CropRepository;
import com.agridispatch.common.ApiException;
import com.agridispatch.common.PartialUpdate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class CropService {
    private final CropRepository cropRepository;

    public CropService(CropRepository cropRepository) {
        this.cropRepository = cropRepository;
    }

    public List<Crop> getAllCrops() {
        return cropRepository.findAll();
    }

    public Optional<Crop> getCropById(String id) {
        return cropRepository.findById(id);
    }

    /** 新增作物：id 必填，已存在回 409（不覆寫） */
    public Crop createCrop(Crop crop) {
        if (crop.getId() == null || crop.getId().isBlank()) {
            throw ApiException.badRequest("作物代號 (id) 為必填欄位");
        }
        if (crop.getName() == null || crop.getName().isBlank()) {
            throw ApiException.badRequest("作物名稱 (name) 為必填欄位");
        }
        validateNumbers(crop);
        if (cropRepository.existsById(crop.getId())) {
            throw ApiException.conflict("作物代號已存在: " + crop.getId());
        }
        return cropRepository.save(crop);
    }

    private static void validateNumbers(Crop crop) {
        if (crop.getBaseHoursPerFen() <= 0 || crop.getWorkersPerFen() <= 0 || crop.getRate() <= 0) {
            throw ApiException.badRequest("每分地工時、人數與費率必須大於 0");
        }
    }

    /** 部分更新：只改 body 中有帶且非 null 的欄位（id 不可改）；名稱明確傳空字串回 400 */
    public Crop updateCrop(String id, Map<String, Object> body) {
        Crop existing = cropRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("找不到該作物代號: " + id));
        PartialUpdate.rejectBlank(body, "name", "作物名稱");
        PartialUpdate.apply(existing, body, Set.of("id"));
        validateNumbers(existing);
        return cropRepository.save(existing);
    }

    public void deleteCrop(String id) {
        if (!cropRepository.existsById(id)) {
            throw ApiException.notFound("找不到該作物代號: " + id);
        }
        cropRepository.deleteById(id);
    }
}
