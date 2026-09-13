package dev.gaurang.wallet.api.dto;

import dev.gaurang.wallet.domain.Transfer;
import dev.gaurang.wallet.domain.TransferKind;
import dev.gaurang.wallet.domain.TransferStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        TransferKind kind,
        UUID from,
        UUID to,
        long amountPaise,
        TransferStatus status,
        String idempotencyKey,
        OffsetDateTime createdAt,
        OffsetDateTime settledAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(transfer.id(), transfer.kind(), transfer.fromWalletId(), transfer.toWalletId(),
                transfer.amountPaise(), transfer.status(), transfer.idempotencyKey(),
                transfer.createdAt(), transfer.settledAt());
    }
}
