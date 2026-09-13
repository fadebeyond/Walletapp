package dev.gaurang.wallet.repository;

import dev.gaurang.wallet.domain.Wallet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    public static final UUID HOUSE_FLOAT_WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String SELECT_COLUMNS =
            "SELECT id, user_id, balance_paise, allow_negative, created_at FROM wallets ";

    private final JdbcClient jdbc;

    public WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * DO UPDATE rather than DO NOTHING: a concurrent duplicate blocks on the winner's row and then
     * reads it back, so we never need a retry loop to see a row that is committed but not yet visible.
     */
    public Upserted<Wallet> getOrCreate(String userId, UUID candidateId) {
        Wallet wallet = jdbc.sql("""
                        INSERT INTO wallets (id, user_id)
                        VALUES (:id, :userId)
                        ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id
                        RETURNING id, user_id, balance_paise, allow_negative, created_at
                        """)
                .param("id", candidateId)
                .param("userId", userId)
                .query(Wallet.class)
                .single();
        return new Upserted<>(wallet, candidateId.equals(wallet.id()));
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbc.sql(SELECT_COLUMNS + "WHERE id = :id").param("id", id).query(Wallet.class).optional();
    }

    public List<Wallet> findAllByIds(List<UUID> ids) {
        return jdbc.sql(SELECT_COLUMNS + "WHERE id IN (:ids)").param("ids", ids).query(Wallet.class).list();
    }

    /** Row lock taken one wallet at a time so the caller controls the ordering and cannot deadlock. */
    public Optional<Wallet> lockForUpdate(UUID id) {
        return jdbc.sql(SELECT_COLUMNS + "WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(Wallet.class)
                .optional();
    }

    /**
     * The whole no-overdraft mechanism: the balance check and the subtraction are one atomic statement,
     * so a losing concurrent debit sees zero rows affected instead of a stale balance.
     */
    public boolean debit(UUID walletId, long amountPaise) {
        int rows = jdbc.sql("""
                        UPDATE wallets
                           SET balance_paise = balance_paise - :amount, updated_at = now()
                         WHERE id = :id
                           AND (balance_paise >= :amount OR allow_negative)
                        """)
                .param("id", walletId)
                .param("amount", amountPaise)
                .update();
        return rows == 1;
    }

    public void credit(UUID walletId, long amountPaise) {
        jdbc.sql("UPDATE wallets SET balance_paise = balance_paise + :amount, updated_at = now() WHERE id = :id")
                .param("id", walletId)
                .param("amount", amountPaise)
                .update();
    }
}
