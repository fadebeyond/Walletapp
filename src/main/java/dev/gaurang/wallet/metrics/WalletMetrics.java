package dev.gaurang.wallet.metrics;

import dev.gaurang.wallet.domain.TransferKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Domain counters, kept next to the RED metrics Actuator already publishes for HTTP. */
@Component
public class WalletMetrics {

    private final MeterRegistry registry;
    private final Counter walletsCreated;
    private final Counter walletsReused;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;

    public WalletMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.walletsCreated = Counter.builder("wallet_wallets_created_total")
                .description("Wallets actually inserted by get-or-create").register(registry);
        this.walletsReused = Counter.builder("wallet_wallets_reused_total")
                .description("Get-or-create calls that returned an existing wallet").register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet_transfers_declined_total")
                .tag("reason", "insufficient_funds").register(registry);
        this.idempotentReplays = Counter.builder("wallet_transfers_idempotent_replays_total")
                .description("Requests served from an already-committed transfer").register(registry);
        this.idempotencyConflicts = Counter.builder("wallet_transfers_idempotency_conflicts_total")
                .description("Same key replayed with a different body").register(registry);
    }

    public void walletCreated() {
        walletsCreated.increment();
    }

    public void walletReused() {
        walletsReused.increment();
    }

    public void transferCreated(TransferKind kind) {
        registry.counter("wallet_transfers_created_total", "kind", kind.name()).increment();
    }

    public void transferCompleted(TransferKind kind) {
        registry.counter("wallet_transfers_completed_total", "kind", kind.name()).increment();
    }

    public void transferDeclinedForInsufficientFunds() {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void idempotencyConflict() {
        idempotencyConflicts.increment();
    }
}
