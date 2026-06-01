package com.example.website.repository;

import com.example.website.entity.NavLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NavLinkRepository extends JpaRepository<NavLink, Long> {
    List<NavLink> findAllByOrderBySortOrderAscIdAsc();

    List<NavLink> findByCategoryIdOrderBySortOrderAscIdAsc(Long categoryId);

    void deleteByCategoryId(Long categoryId);

    List<NavLink> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    List<NavLink> findByCategoryIdAndUserIdOrderBySortOrderAscIdAsc(Long categoryId, Long userId);

    Optional<NavLink> findByIdAndUserId(Long id, Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    void deleteByCategoryIdAndUserId(Long categoryId, Long userId);

    List<NavLink> findByUserIdIsNullOrderByIdAsc();
}
