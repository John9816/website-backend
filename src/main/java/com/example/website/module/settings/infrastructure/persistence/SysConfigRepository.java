package com.example.website.module.settings.infrastructure.persistence;

import com.example.website.module.settings.domain.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {

    Optional<SysConfig> findByConfigKey(String configKey);

    boolean existsByConfigKey(String configKey);

    List<SysConfig> findAllByOrderByConfigKeyAsc();
}
