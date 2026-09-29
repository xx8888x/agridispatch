package com.agridispatch.controller.front;

import com.agridispatch.entity.Talent;
import com.agridispatch.service.TalentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/talents")
public class TalentController {
    private final TalentService talentService;

    public TalentController(TalentService talentService) {
        this.talentService = talentService;
    }

    @GetMapping
    public ResponseEntity<?> getAllTalents() {
        return ResponseEntity.ok(Map.of("success", true, "data", talentService.getAllTalents()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTalentById(@PathVariable String id) {
        return talentService.getTalentById(id)
                .map(talent -> ResponseEntity.ok(Map.of("success", true, "data", talent)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "找不到該人才編號: " + id)));
    }

    @PostMapping
    public ResponseEntity<?> createTalent(@RequestBody Talent talent) {
        Talent created = talentService.createTalent(talent, false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "成功登錄人才"));
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerTalent(@RequestBody Talent talent) {
        Talent created = talentService.createTalent(talent, true);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("success", true, "data", created, "message", "人才登錄成功，已進入調度人才池"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateTalent(@PathVariable String id, @RequestBody Talent talent) {
        Talent updated = talentService.updateTalent(id, talent);
        return ResponseEntity.ok(Map.of("success", true, "data", updated, "message", "成功更新人才資料"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTalent(@PathVariable String id) {
        talentService.deleteTalent(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "成功刪除人才: " + id));
    }
}

