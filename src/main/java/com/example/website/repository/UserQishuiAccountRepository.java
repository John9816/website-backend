package com.example.website.repository;

import com.example.website.entity.UserQishuiAccount;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserQishuiAccountRepository extends JpaRepository<UserQishuiAccount, Long> {
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
}
