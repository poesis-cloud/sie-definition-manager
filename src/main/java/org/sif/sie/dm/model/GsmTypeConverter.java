package org.sif.sie.dm.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link GsmType} to PostgreSQL {@code text} column.
 */
@Converter(autoApply = true)
public class GsmTypeConverter implements AttributeConverter<GsmType, String> {

    @Override
    public String convertToDatabaseColumn(GsmType attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public GsmType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : GsmType.fromValue(dbData);
    }
}
