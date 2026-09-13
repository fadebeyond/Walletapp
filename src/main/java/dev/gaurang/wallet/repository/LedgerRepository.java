package dev.gaurang.wallet.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Both legs in one statement, so a settled transfer can never leave a half-written ledger. */
    public void recordDoubleEntry(UUID transferId, UUID debitWalletId, UUID creditWalletId, long amountPaise) {
        jdbc.sql("""
                        INSERT INTO ledger_entries (transfer_id, wallet_id, amount_paise)
                        VALUES (:transferId, :debitWalletId, :debitAmount),
                               (:transferId, :creditWalletId, :amount)
                        """)
                .param("transferId", transferId)
                .param("debitWalletId", debitWalletId)
                .param("creditWalletId", creditWalletId)
                .param("debitAmount", -amountPaise)
                .param("amount", amountPaise)
                .update();
    }

    public long sumOfAllEntries() {
        return jdbc.sql("SELECT coalesce(sum(amount_paise), 0)::bigint FROM ledger_entries").query(Long.class).single();
    }
}
