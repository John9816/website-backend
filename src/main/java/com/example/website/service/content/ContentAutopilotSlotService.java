package com.example.website.service.content;

import com.example.website.repository.ContentAutopilotSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentAutopilotSlotService {

    private final ContentAutopilotSlotRepository repository;
    private final ContentAutopilotSlotCreator creator;

    public String claim(Long userId, LocalDateTime slotAt, LocalDateTime now) {
        String key = userId + ":" + slotAt;
        try {
            creator.createIfMissing(key, userId, slotAt);
        } catch (DataIntegrityViolationException ignored) {
            // The unique primary key elects one creator across application instances.
        }
        String token = UUID.randomUUID().toString();
        return repository.claim(key, token, now, now.minusHours(2)) == 1 ? token : null;
    }

    public void complete(Long userId, LocalDateTime slotAt, String token, Long articleId) {
        repository.complete(userId + ":" + slotAt, token, articleId, LocalDateTime.now());
    }

    public Long articleId(Long userId, LocalDateTime slotAt) {
        return repository.findById(userId + ":" + slotAt)
                .map(com.example.website.entity.ContentAutopilotSlot::getArticleId)
                .orElse(null);
    }

    public void fail(Long userId, LocalDateTime slotAt, String token, Long articleId, String message) {
        String safe = message == null ? "Unknown error" : message;
        repository.fail(userId + ":" + slotAt, token,
                safe.length() <= 1000 ? safe : safe.substring(0, 1000),
                articleId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(15));
    }
}
