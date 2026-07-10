package com.example.website.service.content;

import com.example.website.dto.content.ContentAgentRunRequest;
import com.example.website.repository.ContentArticleRepository;
import com.example.website.service.ContentArticleService;
import com.example.website.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Unattended content pipeline for the WeChat subscription account.
 *
 * <p>On a fixed cadence it checks the configured daily time slots and, when a slot has come
 * due and has not yet produced an article today, drives {@link ContentArticleService#runAgent}
 * (auto topic -> auto write -> auto WeChat draft, optionally auto group-send). Every knob lives
 * in {@code sys_config} so behaviour can be tuned without a redeploy:
 *
 * <ul>
 *   <li>{@code content.autopilot.enabled} — master switch (default false)</li>
 *   <li>{@code content.autopilot.userId} — owning account; blank disables the pilot</li>
 *   <li>{@code content.autopilot.times} — comma-separated {@code HH:mm} slots, e.g. {@code 08:00,12:30,20:00}</li>
 *   <li>{@code content.autopilot.categories} — comma-separated columns, rotated by slot index</li>
 *   <li>{@code content.autopilot.autoPublish} — attempt API group-send (needs freepublish); default false</li>
 *   <li>{@code content.autopilot.generateCover} — generate a cover image; default true</li>
 * </ul>
 *
 * <p>Slot de-duplication is done against the article table (no extra state table), so a restart
 * mid-day never re-fires an already-served slot. Only the latest due slot is considered on each
 * tick, so starting the app in the evening does not backfill the whole day.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentAutopilotService {

    private static final String DEFAULT_TIMES = "08:00,12:30,20:00";
    private static final String DEFAULT_CATEGORY = "科技 / 互联网";

    private final SysConfigService configService;
    private final ContentArticleService contentArticleService;
    private final ContentArticleRepository articleRepository;

    /** Checked once a minute; cheap no-op unless a slot is both due and unserved. */
    @Scheduled(fixedDelayString = "${content.autopilot.tick-ms:60000}", initialDelay = 30000)
    public void tick() {
        try {
            runOnce(LocalDateTime.now());
        } catch (Exception e) {
            // Never let a scheduler exception kill the recurring task.
            log.warn("[autopilot] tick failed: {}", e.getMessage());
        }
    }

    /**
     * Single evaluation of the pilot at {@code now}. Extracted from {@link #tick()} so the
     * decision logic can be driven from tests with a fixed clock.
     */
    void runOnce(LocalDateTime now) {
        if (!"true".equalsIgnoreCase(config("content.autopilot.enabled"))) {
            return;
        }
        Long userId = parseLong(config("content.autopilot.userId"));
        if (userId == null) {
            return;
        }
        List<LocalTime> slots = parseTimes(config("content.autopilot.times"));
        int slotIndex = dueSlotIndex(slots, now.toLocalTime());
        if (slotIndex < 0) {
            return;
        }
        LocalDateTime slotStart = now.toLocalDate().atTime(slots.get(slotIndex));
        // Already produced something at/after this slot today -> slot is served, skip.
        if (articleRepository.countByUserIdAndCreatedAtAfter(userId, slotStart) > 0) {
            return;
        }

        String category = categoryForSlot(config("content.autopilot.categories"), slotIndex);
        ContentAgentRunRequest req = new ContentAgentRunRequest();
        req.setCategory(category);
        req.setAutoWechatDraft(true);
        req.setAutoPublish("true".equalsIgnoreCase(config("content.autopilot.autoPublish")));
        req.setGenerateCover(!"false".equalsIgnoreCase(config("content.autopilot.generateCover")));

        log.info("[autopilot] running slot {} ({}) for user {} · category={}",
                slotIndex, slots.get(slotIndex), userId, category);
        try {
            contentArticleService.runAgent(userId, req);
            log.info("[autopilot] slot {} done", slotIndex);
        } catch (Exception e) {
            // Leave the slot unserved; the next tick will retry until something lands or the
            // slot window is effectively over for the day (superseded by a later slot).
            log.warn("[autopilot] slot {} failed: {}", slotIndex, e.getMessage());
        }
    }

    // ---- pure helpers (unit-tested) --------------------------------------------------------

    /** Parse comma-separated {@code HH:mm} into a sorted, de-duplicated list; falls back to defaults. */
    static List<LocalTime> parseTimes(String configured) {
        String value = hasText(configured) ? configured : DEFAULT_TIMES;
        List<LocalTime> times = new ArrayList<>();
        for (String part : value.split(",")) {
            String token = trimToNull(part);
            if (token == null) {
                continue;
            }
            try {
                LocalTime parsed = LocalTime.parse(token);
                if (!times.contains(parsed)) {
                    times.add(parsed);
                }
            } catch (Exception ignored) {
                // Skip malformed tokens rather than failing the whole schedule.
            }
        }
        if (times.isEmpty()) {
            for (String part : DEFAULT_TIMES.split(",")) {
                times.add(LocalTime.parse(part.trim()));
            }
        }
        times.sort(LocalTime::compareTo);
        return times;
    }

    /**
     * Index of the latest slot whose time is at/before {@code now}, or -1 if none has come due
     * yet today. Only the most recent due slot matters, so an evening start does not backfill.
     */
    static int dueSlotIndex(List<LocalTime> slots, LocalTime now) {
        int index = -1;
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).isAfter(now)) {
                index = i;
            }
        }
        return index;
    }

    /** Rotate configured categories by slot index; blank config falls back to the default column. */
    static String categoryForSlot(String configured, int slotIndex) {
        List<String> categories = new ArrayList<>();
        if (hasText(configured)) {
            for (String part : configured.split(",")) {
                String token = trimToNull(part);
                if (token != null) {
                    categories.add(token);
                }
            }
        }
        if (categories.isEmpty()) {
            return DEFAULT_CATEGORY;
        }
        return categories.get(Math.floorMod(slotIndex, categories.size()));
    }

    static Long parseLong(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }
}
