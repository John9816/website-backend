package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.CacheConfig;
import com.example.website.dto.SysConfigRequest;
import com.example.website.dto.SysConfigView;
import com.example.website.entity.SysConfig;
import com.example.website.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigRepository configRepository;

    public List<SysConfigView> listAll() {
        return configRepository.findAllByOrderByConfigKeyAsc().stream()
                .map(SysConfigView::from)
                .collect(Collectors.toList());
    }

    public SysConfigView get(Long id) {
        return configRepository.findById(id)
                .map(SysConfigView::from)
                .orElseThrow(() -> new BusinessException(404, "Config not found"));
    }

    @Cacheable(value = CacheConfig.CACHE_SYS_CONFIG, key = "#key",
            unless = "#result == null")
    public Optional<String> getValue(String key) {
        return configRepository.findByConfigKey(key).map(SysConfig::getConfigValue);
    }

    public String getValueOrThrow(String key) {
        return getValue(key).orElseThrow(() ->
                new BusinessException(500, "System config missing: " + key));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SYS_CONFIG, key = "#req.configKey")
    public SysConfigView create(SysConfigRequest req) {
        if (configRepository.existsByConfigKey(req.getConfigKey())) {
            throw new BusinessException(409, "configKey already exists: " + req.getConfigKey());
        }
        SysConfig c = new SysConfig();
        c.setConfigKey(req.getConfigKey());
        c.setConfigValue(req.getConfigValue());
        c.setDescription(req.getDescription());
        return SysConfigView.from(configRepository.save(c));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SYS_CONFIG, allEntries = true)
    public SysConfigView update(Long id, SysConfigRequest req) {
        SysConfig c = configRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "Config not found"));
        if (!c.getConfigKey().equals(req.getConfigKey())
                && configRepository.existsByConfigKey(req.getConfigKey())) {
            throw new BusinessException(409, "configKey already exists: " + req.getConfigKey());
        }
        c.setConfigKey(req.getConfigKey());
        c.setConfigValue(req.getConfigValue());
        c.setDescription(req.getDescription());
        return SysConfigView.from(configRepository.save(c));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SYS_CONFIG, allEntries = true)
    public void delete(Long id) {
        if (!configRepository.existsById(id)) {
            throw new BusinessException(404, "Config not found");
        }
        configRepository.deleteById(id);
    }

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SYS_CONFIG, key = "#key")
    public void seedIfAbsent(String key, String value, String description) {
        if (configRepository.existsByConfigKey(key)) {
            return;
        }
        SysConfig c = new SysConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setDescription(description);
        configRepository.save(c);
    }

    /**
     * Insert or update a config row by its key. Unlike {@link #seedIfAbsent},
     * this overwrites the value (and description when non-null) if the key
     * already exists. Used for keys whose value changes at runtime
     * (e.g. music.tunefree.token written back after a refresh).
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SYS_CONFIG, key = "#key")
    public void upsertByKey(String key, String value, String description) {
        SysConfig c = configRepository.findByConfigKey(key).orElseGet(SysConfig::new);
        if (c.getConfigKey() == null) {
            c.setConfigKey(key);
        }
        c.setConfigValue(value);
        if (description != null) {
            c.setDescription(description);
        }
        configRepository.save(c);
    }
}
