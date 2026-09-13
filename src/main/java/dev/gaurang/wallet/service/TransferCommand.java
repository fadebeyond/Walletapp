package dev.gaurang.wallet.service;

import dev.gaurang.wallet.domain.TransferKind;

import java.util.UUID;

public record TransferCommand(
        TransferKind kind,
        String callerUserId,
        String idempotencyKey,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise) {
}
