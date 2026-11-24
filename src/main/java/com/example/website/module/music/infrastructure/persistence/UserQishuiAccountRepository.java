package com.example.website.module.music.infrastructure.persistence;

import com.example.website.module.music.domain.UserQishuiAccount;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQishuiAccountRepository extends JpaRepository<UserQishuiAccount, Long> {
    /** Scalar projection bypasses a stale managed entity and avoids reading the credential LOB. */
    @Query("select a.bindingId from UserQishuiAccount a where a.userId = :userId")
    java.util.Optional<String> findBindingId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update UserQishuiAccount a set a.bindingId = :next, a.encryptedCredential ="
                + " :encrypted, a.version = a.version + 1 where a.userId = :userId and a.bindingId"
                + " = :expected")
    int replaceIfCurrent(
            @Param("userId") Long userId,
            @Param("expected") String expected,
            @Param("next") String next,
            @Param("encrypted") String encrypted);

    /**
     * Rewrites the credential in place while keeping the binding identity stable.
     *
     * <p>An access-code refresh does not change who the account belongs to, so rotating
     * bindingId there only served to invalidate live roaming sessions. The CAS condition on
     * bindingId still rejects a concurrent rebinding.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update UserQishuiAccount a set a.encryptedCredential = :encrypted, a.version ="
                + " a.version + 1 where a.userId = :userId and a.bindingId = :expected")
    int updateIfCurrent(
            @Param("userId") Long userId,
            @Param("expected") String expected,
            @Param("encrypted") String encrypted);

    /** Scalar projection for the scheduled auth-refresh sweep; never loads the credential LOB. */
    @Query("select a.userId from UserQishuiAccount a")
    java.util.List<Long> findAllUserIds();
}
