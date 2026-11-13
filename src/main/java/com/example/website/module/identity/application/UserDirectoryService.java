package com.example.website.module.identity.application;

import com.example.website.module.identity.api.dto.UserBriefView;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only display-name lookup for other modules. Keeps identity persistence
 * private so cross-module consumers stay in the application layer.
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final UserRepository userRepository;

    /** Only enabled users are returned; callers never see email/role/password. */
    public Map<Long, UserBriefView> briefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        Map<Long, UserBriefView> result = new HashMap<>();
        userRepository.findAllById(userIds).forEach(user -> {
            if (user.isEnabled()) {
                result.put(user.getId(), new UserBriefView(user.getUsername(), user.getAvatarUrl()));
            }
        });
        return result;
    }
}
