package dev.gaurang.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The three properties the exercise grades, each driven through the HTTP API under real concurrency. */
class InvariantsTest extends AbstractIntegrationTest {

    private static final long SEED_PAISE = 100_000L;

    @Test
    @DisplayName("50 concurrent get-or-create calls yield one wallet")
    void concurrentGetOrCreateYieldsOneWallet() {
        List<UUID> walletIds = fireSimultaneously(50, () -> createWallet(token));

        assertThat(Set.copyOf(walletIds)).hasSize(1);
    }

    @Test
    @DisplayName("30 concurrent sends of the same idempotency key debit once")
    void idempotentRetryStormAppliesOnce() {
        String senderToken = issueToken("sender-" + UUID.randomUUID());
        String receiverToken = issueToken("receiver-" + UUID.randomUUID());
        UUID sender = createWallet(senderToken);
        UUID receiver = createWallet(receiverToken);
        topUp(senderToken, sender, SEED_PAISE);
        String key = "storm-" + UUID.randomUUID();

        List<ResponseEntity<Map>> responses =
                fireSimultaneously(30, () -> transfer(senderToken, sender, receiver, 5_000L, key));

        Set<Object> transferIds = responses.stream().map(r -> r.getBody().get("id")).collect(Collectors.toSet());
        assertThat(transferIds).hasSize(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(balanceOf(senderToken, sender)).isEqualTo(SEED_PAISE - 5_000L);
        assertThat(balanceOf(receiverToken, receiver)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("the same key with a different body is a conflict, not a second debit")
    void reusedKeyWithDifferentBodyConflicts() {
        String senderToken = issueToken("sender-" + UUID.randomUUID());
        UUID sender = createWallet(senderToken);
        UUID receiver = createWallet(issueToken("receiver-" + UUID.randomUUID()));
        topUp(senderToken, sender, SEED_PAISE);
        String key = "reused-" + UUID.randomUUID();
        transfer(senderToken, sender, receiver, 5_000L, key);

        ResponseEntity<Map> conflict = transfer(senderToken, sender, receiver, 9_999L, key);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf(senderToken, sender)).isEqualTo(SEED_PAISE - 5_000L);
    }

    @Test
    @DisplayName("money is conserved when A->B and B->A collide, and overdrafts decline cleanly")
    void conservationHoldsUnderContention() {
        List<String> tokens = IntStream.range(0, 4)
                .mapToObj(i -> issueToken("player-" + i + "-" + UUID.randomUUID())).toList();
        List<UUID> wallets = tokens.stream().map(this::createWallet).toList();
        IntStream.range(0, 4).forEach(i -> topUp(tokens.get(i), wallets.get(i), SEED_PAISE));
        long totalBefore = totalOf(tokens, wallets);

        AtomicInteger sequence = new AtomicInteger();
        fireSimultaneously(200, () -> {
            int n = sequence.getAndIncrement();
            int from = n % 4;
            // Odd rounds run the pair backwards, so A->B and B->A are in flight together.
            int to = n % 2 == 0 ? (from + 1) % 4 : (from + 3) % 4;
            long amount = n % 17 == 0 ? SEED_PAISE * 50 : 700L;   // deliberately unaffordable
            return transfer(tokens.get(from), wallets.get(from), wallets.get(to), amount,
                    "load-" + UUID.randomUUID());
        });

        assertThat(totalOf(tokens, wallets)).isEqualTo(totalBefore);
        IntStream.range(0, 4).forEach(i ->
                assertThat(balanceOf(tokens.get(i), wallets.get(i))).isNotNegative());
    }

    private long totalOf(List<String> tokens, List<UUID> wallets) {
        return IntStream.range(0, wallets.size()).mapToLong(i -> balanceOf(tokens.get(i), wallets.get(i))).sum();
    }
}
