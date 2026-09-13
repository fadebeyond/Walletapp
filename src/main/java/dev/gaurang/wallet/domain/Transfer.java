package dev.gaurang.wallet.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Transfer(
        UUID id,
        TransferKind kind,
        String initiatedBy,
        String idempotencyKey,
        String requestFingerprint,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        TransferStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime settledAt) {
}
