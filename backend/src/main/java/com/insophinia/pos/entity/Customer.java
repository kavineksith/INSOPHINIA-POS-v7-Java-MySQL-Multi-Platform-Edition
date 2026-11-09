package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String email;

    @Column(unique = true, length = 20)
    private String phone;

    @Column(name = "loyalty_points", nullable = false)
    private int loyaltyPoints = 0;

    // --------------------------------------------------------------------------
    // Email verification lifecycle (included)
    // --------------------------------------------------------------------------
    // The Spring backend seeds these on customer create/update when a non-empty
    // email is provided. Verification tokens are 64-hex (128 chars when URL-safe
    // base64-encoded, but stored as raw hex) and expire after 24 hours.
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "email_verification_token", length = 128)
    private String emailVerificationToken;

    @Column(name = "email_verification_expires_at")
    private Instant emailVerificationExpiresAt;
}
