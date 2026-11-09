package com.insophinia.pos.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Keeps the Java identifier bool compatible with the the database schema/database literal boolean. */
@Converter
public class SettingTypeConverter implements AttributeConverter<SettingType, String> {
    @Override public String convertToDatabaseColumn(SettingType value) {
        if (value == null) return null; return value == SettingType.bool ? "boolean" : value.name();
    }
    @Override public SettingType convertToEntityAttribute(String value) {
        if (value == null) return null; return "boolean".equals(value) ? SettingType.bool : SettingType.valueOf(value);
    }
}
