package com.example.website.service.content;

import com.example.website.entity.ContentAutopilotSlot;
import com.example.website.repository.ContentAutopilotSlotRepository;
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
