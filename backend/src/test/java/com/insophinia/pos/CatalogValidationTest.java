package com.insophinia.pos;

import com.insophinia.pos.dto.CatalogDtos.CustomerRequest;
import com.insophinia.pos.dto.CatalogDtos.PromotionRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CatalogValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void customerEmailMustBeValidWhenProvided() {
        assertFalse(validator.validate(new CustomerRequest("Customer", "not-an-email", "0712345678")).isEmpty());
    }

    @Test
    void promotionMustRequireAtLeastOneItem() {
        PromotionRequest request = new PromotionRequest(null, null, "Bulk deal", null, "percentage",
                BigDecimal.TEN, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T23:59:59Z"), true, 0);
        assertFalse(validator.validate(request).isEmpty());
    }
}
