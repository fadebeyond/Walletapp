package dev.gaurang.wallet.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvariantsRepository {

    private final JdbcClient jdbc;

    public InvariantsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(long walletCount, long userBalanceTotalPaise, long houseFloatBalancePaise,
                           long negativeBalanceWallets) {
    }

    public Snapshot snapshot() {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE NOT allow_negative)                          AS wallet_count,
                               coalesce(sum(balance_paise) FILTER (WHERE NOT allow_negative), 0)::bigint AS user_balance_total_paise,
                               coalesce(sum(balance_paise) FILTER (WHERE allow_negative), 0)::bigint     AS house_float_balance_paise,
                               count(*) FILTER (WHERE NOT allow_negative AND balance_paise < 0)    AS negative_balance_wallets
                          FROM wallets
                        """)
                .query(Snapshot.class)
                .single();
    }
}
