package dev.gaurang.wallet.api.dto;

public final class MoneyLimits {

    /** Ten crore rupees per movement, far below the point where a bigint balance could overflow. */
    public static final long MAX_AMOUNT_PAISE = 10_000_000_000L;

    private MoneyLimits() {
    }
}
