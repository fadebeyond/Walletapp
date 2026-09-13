package dev.gaurang.wallet.repository;

/** Tells an upsert caller whether it won the insert race or read back somebody else's row. */
public record Upserted<T>(T row, boolean inserted) {
}
