package dev.gaurang.wallet.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Wallet(UUID id, String userId, long balancePaise, boolean allowNegative, OffsetDateTime createdAt) {

    public boolean isOwnedBy(String candidateUserId) {
        return userId.equals(candidateUserId);
    }
}
