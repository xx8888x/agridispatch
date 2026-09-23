package com.agridispatch.service;

import com.agridispatch.entity.Crop;
import com.agridispatch.repository.CropRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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

    public Crop createCrop(Crop crop) {
        return cropRepository.save(crop);
    }

    public Crop updateCrop(String id, Crop updated) {
        return cropRepository.findById(id).map(existing -> {
            existing.setName(updated.getName());
            existing.setBaseHoursPerFen(updated.getBaseHoursPerFen());
            existing.setWorkersPerFen(updated.getWorkersPerFen());
            existing.setRate(updated.getRate());
            existing.setSeason(updated.getSeason());
            existing.setTaskDescription(updated.getTaskDescription());
            existing.setIndoorAlternative(updated.getIndoorAlternative());
            return cropRepository.save(existing);
        }).orElseThrow(() -> new RuntimeException("Crop not found: " + id));
    }

    public void deleteCrop(String id) {
        cropRepository.deleteById(id);
    }
}
