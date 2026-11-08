package com.example.website.module.membership.infrastructure.persistence;

import com.example.website.module.membership.domain.RedeemCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.Optional;

public interface RedeemCodeRepository extends JpaRepository<RedeemCode, Long>, JpaSpecificationExecutor<RedeemCode> {

    boolean existsByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RedeemCode c where c.codeHash = :codeHash")
    Optional<RedeemCode> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RedeemCode c where c.id = :id")
    Optional<RedeemCode> findByIdForUpdate(@Param("id") Long id);
}
