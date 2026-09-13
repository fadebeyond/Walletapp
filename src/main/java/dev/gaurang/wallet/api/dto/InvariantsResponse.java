package dev.gaurang.wallet.api.dto;

public record InvariantsResponse(
        long walletCount,
        long userBalanceTotalPaise,
        long houseFloatBalancePaise,
        long ledgerSumPaise,
        long negativeBalanceWallets,
        boolean conserved) {
}
