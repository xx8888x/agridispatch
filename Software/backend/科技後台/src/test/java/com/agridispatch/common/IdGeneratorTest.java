package com.agridispatch.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdGeneratorTest {

    @Test
    void usesMaxPlusOneNotCount() {
        // 刪除過中間資料：count()+1 = 3 會撞到既有 ORD-2026-003
        List<String> ids = List.of("ORD-2026-001", "ORD-2026-003");
        assertEquals("ORD-2026-004", IdGenerator.next("ORD-2026-", 3, ids, Set.copyOf(ids)::contains));
    }

    @Test
    void skipsExistingAndIgnoresForeignFormats() {
        List<String> ids = List.of("W001", "W030", "WX99", "W");
        Set<String> exists = Set.of("W001", "W030", "W031");
        assertEquals("W032", IdGenerator.next("W", 3, ids, exists::contains));
        assertEquals("TAL-001", IdGenerator.next("TAL-", 3, List.of(), s -> false));
    }
}
