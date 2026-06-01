package com.example.website.repository;

import com.example.website.entity.KbSpace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KbSpaceRepository extends JpaRepository<KbSpace, Long> {

    List<KbSpace> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    Optional<KbSpace> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserId(Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query("update KbSpace s set s.docCount = s.docCount + :delta where s.id = :id")
    int adjustDocCount(@Param("id") Long id, @Param("delta") int delta);
}
