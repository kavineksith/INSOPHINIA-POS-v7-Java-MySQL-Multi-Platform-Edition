package com.insophinia.pos.repository;

import com.insophinia.pos.entity.DeviceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeviceProfileRepository extends JpaRepository<DeviceProfile, UUID> {
    Optional<DeviceProfile> findByDeviceIdentifier(String deviceIdentifier);
}
