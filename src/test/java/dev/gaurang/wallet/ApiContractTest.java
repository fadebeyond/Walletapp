package dev.gaurang.wallet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Edge cases around the money path that are cheaper to pin down here than to discover in production. */
class ApiContractTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("a request without a bearer token is rejected")
    void unauthenticatedRequestIsRejected() {
        assertThat(exchange(HttpMethod.POST, "/wallets", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a wallet belonging to somebody else is not readable")
    void foreignWalletIsForbidden() {
        UUID otherWallet = createWallet(issueToken("other-" + UUID.randomUUID()));

        assertThat(exchange(HttpMethod.GET, "/wallets/" + otherWallet, token, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a decimal amount is rejected rather than truncated into paise")
    void decimalAmountIsRejected() {
        UUID wallet = createWallet(token);
        ResponseEntity<Map> response = exchange(HttpMethod.POST, "/transfers", token,
                Map.of("from", wallet, "to", UUID.randomUUID(), "amount_paise", 10.5,
                        "idempotency_key", "decimal-" + UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a transfer to the same wallet is rejected")
    void selfTransferIsRejected() {
        UUID wallet = createWallet(token);

        assertThat(transfer(token, wallet, wallet, 100L, "self-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a zero or negative amount is rejected")
    void nonPositiveAmountIsRejected() {
        UUID wallet = createWallet(token);
        UUID other = createWallet(issueToken("other-" + UUID.randomUUID()));

        assertThat(transfer(token, wallet, other, 0L, "zero-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer(token, wallet, other, -500L, "neg-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a transfer to an unknown wallet is a 404")
    void unknownWalletIsNotFound() {
        UUID wallet = createWallet(token);
        topUp(token, wallet, 10_000L);

        assertThat(transfer(token, wallet, UUID.randomUUID(), 100L, "ghost-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unaffordable transfer declines without moving money")
    void insufficientFundsDeclinesCleanly() {
        String senderToken = issueToken("poor-" + UUID.randomUUID());
        UUID sender = createWallet(senderToken);
        UUID receiver = createWallet(issueToken("rich-" + UUID.randomUUID()));
        topUp(senderToken, sender, 1_000L);

        ResponseEntity<Map> declined = transfer(senderToken, sender, receiver, 5_000L, "broke-" + UUID.randomUUID());

        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(declined.getBody()).containsEntry("status", "DECLINED_INSUFFICIENT_FUNDS");
        assertThat(balanceOf(senderToken, sender)).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("replaying a declined transfer returns the same decline")
    void declinedTransferReplaysAsDeclined() {
        String senderToken = issueToken("poor-" + UUID.randomUUID());
        UUID sender = createWallet(senderToken);
        UUID receiver = createWallet(issueToken("rich-" + UUID.randomUUID()));
        topUp(senderToken, sender, 1_000L);
        String key = "decline-replay-" + UUID.randomUUID();
        Object firstId = transfer(senderToken, sender, receiver, 5_000L, key).getBody().get("id");

        ResponseEntity<Map> replay = transfer(senderToken, sender, receiver, 5_000L, key);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(replay.getBody().get("id")).isEqualTo(firstId);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
    }

    @Test
    @DisplayName("health and metrics are reachable without a token, so probes and scrapers work")
    void operationalEndpointsAreUnauthenticated() {
        assertThat(exchange(HttpMethod.GET, "/health", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/metrics", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("every response carries a correlation id")
    void correlationIdIsEchoed() {
        assertThat(exchange(HttpMethod.POST, "/wallets", token, null).getHeaders().getFirst("X-Correlation-Id"))
                .isNotBlank();
    }
}
