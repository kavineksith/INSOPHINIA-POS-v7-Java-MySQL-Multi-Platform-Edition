package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "settings")
public class Setting extends BaseEntity {

    // NOTE: `key` and `value` are MySQL reserved words. Hibernate's MySQL
    // dialect auto-quotes reserved-word identifiers with backticks, so we
    // declare the bare column names here (Hibernate auto-quotes MySQL reserved words).
    // The Flyway migration V1 uses `\`key\`` and `\`value\`` backticks in
    // CREATE TABLE for the same reason.
    @Column(name = "key", nullable = false, unique = true, length = 100)
    private String key;

    @Column(name = "value", columnDefinition = "TEXT")
    private String value;

    @Convert(converter = SettingTypeConverter.class)
    @Column(name = "type", nullable = false, length = 20)
    private SettingType type = SettingType.string;

    @Column(columnDefinition = "TEXT")
    private String description;
}
