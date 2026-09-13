package dev.gaurang.wallet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TopUpRequest(
        @NotNull @Positive @Max(MoneyLimits.MAX_AMOUNT_PAISE) Long amountPaise,
        @NotBlank @Size(max = 255) String idempotencyKey) {
}
