package dev.gaurang.wallet.repository;

import dev.gaurang.wallet.domain.Transfer;
import dev.gaurang.wallet.domain.TransferStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private static final String RETURNING_COLUMNS = """
            RETURNING id, kind, initiated_by, idempotency_key, request_fingerprint,
                      from_wallet_id, to_wallet_id, amount_paise, status, created_at, settled_at
            """;

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the idempotency key in the same transaction as the ledger movement that follows.
     * A duplicate blocks here until the winner commits, then reads back the winner's final row.
     */
    public Upserted<Transfer> claimIdempotencyKey(Transfer candidate) {
        Transfer stored = jdbc.sql("""
                        INSERT INTO transfers (id, kind, initiated_by, idempotency_key, request_fingerprint,
                                               from_wallet_id, to_wallet_id, amount_paise, status)
                        VALUES (:id, :kind, :initiatedBy, :idempotencyKey, :fingerprint,
                                :fromWalletId, :toWalletId, :amount, 'PENDING')
                        ON CONFLICT (initiated_by, idempotency_key)
                        DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
                        """ + RETURNING_COLUMNS)
                .param("id", candidate.id())
                .param("kind", candidate.kind().name())
                .param("initiatedBy", candidate.initiatedBy())
                .param("idempotencyKey", candidate.idempotencyKey())
                .param("fingerprint", candidate.requestFingerprint())
                .param("fromWalletId", candidate.fromWalletId())
                .param("toWalletId", candidate.toWalletId())
                .param("amount", candidate.amountPaise())
                .query(Transfer.class)
                .single();
        return new Upserted<>(stored, candidate.id().equals(stored.id()));
    }

    public Transfer settle(UUID transferId, TransferStatus status) {
        return jdbc.sql("""
                        UPDATE transfers
                           SET status = :status,
                               settled_at = now()
                         WHERE id = :id
                        """ + RETURNING_COLUMNS)
                .param("id", transferId)
                .param("status", status.name())
                .query(Transfer.class)
                .single();
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, kind, initiated_by, idempotency_key, request_fingerprint,
                               from_wallet_id, to_wallet_id, amount_paise, status, created_at, settled_at
                          FROM transfers WHERE id = :id
                        """)
                .param("id", id)
                .query(Transfer.class)
                .optional();
    }

}
