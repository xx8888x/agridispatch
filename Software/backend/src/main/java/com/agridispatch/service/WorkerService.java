package com.agridispatch.service;

import com.agridispatch.entity.Worker;
import com.agridispatch.repository.WorkerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class WorkerService {
    private final WorkerRepository workerRepository;

    public WorkerService(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    public List<Worker> getAllWorkers() {
        return workerRepository.findAll();
    }

    public Optional<Worker> getWorkerById(String id) {
        return workerRepository.findById(id);
    }

    public List<Worker> getWorkersByTeam(String teamId) {
        return workerRepository.findByTeamId(teamId);
    }

    public List<Worker> getWorkersByTier(String tier) {
        return workerRepository.findByTier(tier);
    }

    public Worker createWorker(Worker worker) {
        if (worker.getId() == null || worker.getId().trim().isEmpty()) {
            worker.setId("W" + String.format("%03d", workerRepository.count() + 1));
        }
        return workerRepository.save(worker);
    }

    public Worker updateWorker(String id, Worker updated) {
        return workerRepository.findById(id).map(existing -> {
            existing.setName(updated.getName());
            existing.setChineseName(updated.getChineseName());
            existing.setNationality(updated.getNationality());
            existing.setTeamId(updated.getTeamId());
            existing.setLeader(updated.isLeader());
            existing.setTier(updated.getTier());
            existing.setBaseSalary(updated.getBaseSalary());
            existing.setAgriBonus(updated.getAgriBonus());
            existing.setTransportAllowance(updated.getTransportAllowance());
            existing.setArcNumber(updated.getArcNumber());
            existing.setPassportNumber(updated.getPassportNumber());
            existing.setPermitExpiry(updated.getPermitExpiry());
            existing.setInsuranceStatus(updated.getInsuranceStatus());
            existing.setCurrentTask(updated.getCurrentTask());
            existing.setDispatchStatus(updated.getDispatchStatus());
            existing.setPhone(updated.getPhone());
            return workerRepository.save(existing);
        }).orElseThrow(() -> new RuntimeException("Worker not found: " + id));
    }

    public void deleteWorker(String id) {
        workerRepository.deleteById(id);
    }
}
