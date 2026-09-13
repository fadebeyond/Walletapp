package dev.gaurang.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueTokenRequest(@NotBlank @Size(max = 128) String userId) {
}
