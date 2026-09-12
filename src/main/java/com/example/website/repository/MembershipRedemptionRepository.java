package com.example.website.repository;

import com.example.website.entity.MembershipRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRedemptionRepository extends JpaRepository<MembershipRedemption, Long> {
}
