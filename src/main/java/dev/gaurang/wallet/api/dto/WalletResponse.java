package dev.gaurang.wallet.api.dto;

import dev.gaurang.wallet.domain.Wallet;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WalletResponse(UUID id, String userId, long balancePaise, OffsetDateTime createdAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.balancePaise(), wallet.createdAt());
    }
}
