package com.example.website.module.membership.infrastructure.persistence;

import com.example.website.module.membership.domain.UserMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.Optional;

public interface UserMembershipRepository extends JpaRepository<UserMembership, Long> {

    Optional<UserMembership> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from UserMembership m where m.userId = :userId")
    Optional<UserMembership> findByUserIdForUpdate(@Param("userId") Long userId);
}
