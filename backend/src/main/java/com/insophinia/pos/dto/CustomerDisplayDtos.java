package com.insophinia.pos.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class CustomerDisplayDtos {
    private CustomerDisplayDtos() {}
    public record CartLine(@NotBlank @Size(max = 120) String name, @NotNull @DecimalMin("0.001") BigDecimal qty, @NotNull @DecimalMin("0") BigDecimal price) {}
    public record CartSnapshot(@NotBlank @Size(min = 16, max = 128) String channel, @NotBlank @Size(max = 80) String terminalId, @NotNull @Size(max = 100) List<@Valid CartLine> items, @NotNull @DecimalMin("0") BigDecimal total, @NotBlank @Size(max = 8) String currency) {}
}
