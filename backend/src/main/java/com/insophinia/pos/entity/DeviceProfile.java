package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "device_profiles")
public class DeviceProfile extends BaseEntity {

    @Column(name = "device_identifier", nullable = false, unique = true)
    private String deviceIdentifier;

    @Column(nullable = false, length = 100)
    private String name = "New Device";

    @Column(name = "default_printer", nullable = false, length = 50)
    private String defaultPrinter = "thermal";

    @Column(name = "default_scanner", nullable = false, length = 50)
    private String defaultScanner = "keyboard";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
