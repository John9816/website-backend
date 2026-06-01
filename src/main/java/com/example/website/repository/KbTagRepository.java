package com.example.website.repository;

import com.example.website.entity.KbTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KbTagRepository extends JpaRepository<KbTag, Long> {

    List<KbTag> findByUserIdOrderByNameAsc(Long userId);

    Optional<KbTag> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    Optional<KbTag> findByUserIdAndName(Long userId, String name);

    long countByIdInAndUserId(Collection<Long> ids, Long userId);

    List<KbTag> findByIdInAndUserId(Collection<Long> ids, Long userId);
}
