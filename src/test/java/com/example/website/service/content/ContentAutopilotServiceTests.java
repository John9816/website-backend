package com.example.website.service.content;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContentAutopilotServiceTests {

    @Test
    void parseTimesSortsDeDupesAndSkipsMalformed() {
        List<LocalTime> times = ContentAutopilotService.parseTimes("20:00, 08:00 , 08:00, oops, 12:30");

        assertEquals(3, times.size());
        assertEquals(LocalTime.of(8, 0), times.get(0));
        assertEquals(LocalTime.of(12, 30), times.get(1));
        assertEquals(LocalTime.of(20, 0), times.get(2));
    }

    @Test
    void parseTimesFallsBackToDefaultsWhenBlankOrAllInvalid() {
        List<LocalTime> blank = ContentAutopilotService.parseTimes("   ");
        List<LocalTime> garbage = ContentAutopilotService.parseTimes("nope, also-nope");

        assertEquals(Arrays.asList(LocalTime.of(8, 0), LocalTime.of(12, 30), LocalTime.of(20, 0)), blank);
        assertEquals(blank, garbage);
    }

    @Test
    void dueSlotIndexReturnsLatestSlotAtOrBeforeNow() {
        List<LocalTime> slots = Arrays.asList(LocalTime.of(8, 0), LocalTime.of(12, 30), LocalTime.of(20, 0));

        assertEquals(-1, ContentAutopilotService.dueSlotIndex(slots, LocalTime.of(7, 59)));
        assertEquals(0, ContentAutopilotService.dueSlotIndex(slots, LocalTime.of(8, 0)));
        assertEquals(0, ContentAutopilotService.dueSlotIndex(slots, LocalTime.of(12, 29)));
        assertEquals(1, ContentAutopilotService.dueSlotIndex(slots, LocalTime.of(12, 30)));
        assertEquals(2, ContentAutopilotService.dueSlotIndex(slots, LocalTime.of(23, 59)));
    }

    @Test
    void categoryForSlotRotatesByIndexAndFallsBackWhenBlank() {
        String configured = "A,B,C";

        assertEquals("A", ContentAutopilotService.categoryForSlot(configured, 0));
        assertEquals("C", ContentAutopilotService.categoryForSlot(configured, 2));
        assertEquals("A", ContentAutopilotService.categoryForSlot(configured, 3));
        assertEquals("科技 / 互联网", ContentAutopilotService.categoryForSlot("  ", 1));
    }

    @Test
    void parseLongReturnsNullForBlankOrNonNumeric() {
        assertEquals(42L, ContentAutopilotService.parseLong(" 42 "));
        assertNull(ContentAutopilotService.parseLong(""));
        assertNull(ContentAutopilotService.parseLong("abc"));
        assertNull(ContentAutopilotService.parseLong(null));
    }
}
