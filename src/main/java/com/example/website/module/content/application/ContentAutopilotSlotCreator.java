package com.example.website.module.content.application;

import com.example.website.module.content.domain.ContentAutopilotSlot;
import com.example.website.module.content.infrastructure.persistence.ContentAutopilotSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ContentAutopilotSlotCreator {

    private final ContentAutopilotSlotRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfMissing(String key, Long userId, LocalDateTime slotAt) {
        if (repository.existsById(key)) return;
        ContentAutopilotSlot slot = new ContentAutopilotSlot();
        slot.setSlotKey(key);
        slot.setUserId(userId);
        slot.setSlotAt(slotAt);
        repository.saveAndFlush(slot);
    }
}
