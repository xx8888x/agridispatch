package com.agridispatch.controller.common;

import com.agridispatch.service.CalendarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping
    public ResponseEntity<?> getCalendarOverview(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) {
        int selectedYear = (year != null && year >= 2024 && year <= 2035) ? year : java.time.LocalDate.now().getYear();
        int selectedMonth = (month != null && month >= 1 && month <= 12) ? month : java.time.LocalDate.now().getMonthValue();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "selectedYear", selectedYear,
                "selectedMonth", selectedMonth,
                "quarters", calendarService.getQuarterCalendar(),
                "months", calendarService.getMonthlyCalendar(),
                "upcomingHarvests", calendarService.getUpcomingHarvests(selectedYear, selectedMonth)
        ));
    }

    @GetMapping("/harvests")
    public ResponseEntity<?> getHarvests(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) {
        int selectedYear = (year != null && year >= 2024 && year <= 2035) ? year : java.time.LocalDate.now().getYear();
        int selectedMonth = (month != null && month >= 1 && month <= 12) ? month : java.time.LocalDate.now().getMonthValue();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "selectedYear", selectedYear,
                "selectedMonth", selectedMonth,
                "data", calendarService.getUpcomingHarvests(selectedYear, selectedMonth)
        ));
    }

    @GetMapping("/quarters")
    public ResponseEntity<?> getQuarters() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", calendarService.getQuarterCalendar()
        ));
    }

    @GetMapping("/months")
    public ResponseEntity<?> getMonths() {
        return ResponseEntity.ok(Map.of(
                "success", true,
                "data", calendarService.getMonthlyCalendar()
        ));
    }
}

