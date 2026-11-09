package com.insophinia.pos.repository;

import com.insophinia.pos.entity.User;
import com.insophinia.pos.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
    Optional<User> findByIdAndDeletedAtIsNull(UUID id);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    long countByRoleAndDeletedAtIsNull(UserRole role);
}
