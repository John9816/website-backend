package com.example.website.module.membership.infrastructure.persistence;

import com.example.website.module.membership.domain.MembershipRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRedemptionRepository extends JpaRepository<MembershipRedemption, Long> {
}
