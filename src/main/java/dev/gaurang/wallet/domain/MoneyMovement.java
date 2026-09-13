package dev.gaurang.wallet.domain;

import java.util.UUID;

/** One debit/credit pair, ordered so callers always lock the lower wallet id first. */
public record MoneyMovement(UUID debitWalletId, UUID creditWalletId, long amountPaise) {

    public UUID firstLockTarget() {
        return debitWalletId.compareTo(creditWalletId) <= 0 ? debitWalletId : creditWalletId;
    }

    public UUID secondLockTarget() {
        return debitWalletId.compareTo(creditWalletId) <= 0 ? creditWalletId : debitWalletId;
    }
}
