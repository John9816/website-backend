package com.example.website.module.settings.application;

import com.example.website.module.settings.api.dto.SysConfigRequest;
import com.example.website.module.settings.api.dto.SysConfigView;
import com.example.website.module.settings.domain.SysConfig;
import com.example.website.module.settings.infrastructure.persistence.SysConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysConfigSecretTests {

    @Test
    void secretViewsNeverReturnTheirValues() {
        SysConfig secret = config("wechat.appSecret", "private-value");
        SysConfigView view = SysConfigView.from(secret);

        assertTrue(view.isSensitive());
        assertTrue(view.isConfigured());
        assertNull(view.getConfigValue());
        assertTrue(SysConfigView.isSensitiveKey("image.api.key"));
        assertTrue(SysConfigView.isSensitiveKey("music.tunefree.password"));
        assertTrue(SysConfigView.isSensitiveKey("music.qqText.apiKey"));

        SysConfigView ordinary = SysConfigView.from(config("content.autopilot.times", "08:00"));
        assertFalse(ordinary.isSensitive());
        assertEquals("08:00", ordinary.getConfigValue());
    }

    @Test
    void updateCanPreserveOrReplaceSecretWithoutReadingItBack() {
        SysConfigRepository repository = mock(SysConfigRepository.class);
        SysConfigService service = new SysConfigService(repository);
        SysConfig secret = config("wechat.appSecret", "old-secret");
        secret.setId(17L);
        when(repository.findById(17L)).thenReturn(Optional.of(secret));
        when(repository.save(any(SysConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SysConfigRequest unchanged = request("wechat.appSecret", null, true);
        SysConfigView preserved = service.update(17L, unchanged);
        assertEquals("old-secret", secret.getConfigValue());
        assertNull(preserved.getConfigValue());
        verify(repository).save(secret);

        SysConfigView replaced = service.update(17L, request("wechat.appSecret", "new-secret", false));
        assertEquals("new-secret", secret.getConfigValue());
        assertNull(replaced.getConfigValue());
    }

    private static SysConfig config(String key, String value) {
        SysConfig config = new SysConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        return config;
    }

    private static SysConfigRequest request(String key, String value, boolean preserve) {
        SysConfigRequest request = new SysConfigRequest();
        request.setConfigKey(key);
        request.setConfigValue(value);
        request.setPreserveValue(preserve);
        return request;
    }
}
