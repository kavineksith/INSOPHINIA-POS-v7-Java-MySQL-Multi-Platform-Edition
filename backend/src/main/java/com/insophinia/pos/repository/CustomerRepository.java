package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {
    Optional<Customer> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Customer> findByEmailAndDeletedAtIsNull(String email);
    Optional<Customer> findByPhoneAndDeletedAtIsNull(String phone);
    Page<Customer> findByDeletedAtIsNull(Pageable pageable);

    /** Used by the public GET /api/customers/verify-email?token=... endpoint. */
    Optional<Customer> findByEmailVerificationTokenAndDeletedAtIsNull(String token);

    @org.springframework.data.jpa.repository.Query("select coalesce(sum(c.loyaltyPoints), 0) from Customer c where c.deletedAt is null")
    long sumLoyaltyPoints();
    long countByDeletedAtIsNull();
}
