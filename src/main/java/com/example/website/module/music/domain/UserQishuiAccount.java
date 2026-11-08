package com.example.website.module.music.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Version;

@Entity
@Table(name = "user_qishui_account")
@Getter
@Setter
@NoArgsConstructor
public class UserQishuiAccount {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "encrypted_credential", nullable = false, columnDefinition = "LONGTEXT")
    private String encryptedCredential;

    @Column(name = "binding_id", nullable = false, length = 36)
    private String bindingId;

    @Version
    @Column(nullable = false)
    private Long version;
}
