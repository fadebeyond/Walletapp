package dev.gaurang.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    protected TestRestTemplate rest;

    protected String token;

    @BeforeEach
    void issueDefaultToken() {
        token = issueToken("user-" + UUID.randomUUID());
    }

    protected String issueToken(String userId) {
        Map<?, ?> body = rest.postForObject("/auth/tokens", Map.of("user_id", userId), Map.class);
        return (String) body.get("token");
    }

    protected UUID createWallet(String bearer) {
        return UUID.fromString((String) exchange(HttpMethod.POST, "/wallets", bearer, null).getBody().get("id"));
    }

    protected void topUp(String bearer, UUID walletId, long amountPaise) {
        exchange(HttpMethod.POST, "/wallets/" + walletId + "/topups", bearer,
                Map.of("amount_paise", amountPaise, "idempotency_key", "topup-" + UUID.randomUUID()));
    }

    protected long balanceOf(String bearer, UUID walletId) {
        Object balance = exchange(HttpMethod.GET, "/wallets/" + walletId, bearer, null).getBody().get("balance_paise");
        return ((Number) balance).longValue();
    }

    protected ResponseEntity<Map> transfer(String bearer, UUID from, UUID to, long amountPaise, String idempotencyKey) {
        return exchange(HttpMethod.POST, "/transfers", bearer,
                Map.of("from", from, "to", to, "amount_paise", amountPaise, "idempotency_key", idempotencyKey));
    }

    @SuppressWarnings("unchecked")
    protected ResponseEntity<Map> exchange(HttpMethod method, String path, String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    /** Releases every task at once, so the database really does see simultaneous requests. */
    protected <T> List<T> fireSimultaneously(int count, Callable<T> task) {
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = IntStream.range(0, count)
                    .mapToObj(i -> pool.submit(() -> {
                        startGun.await();
                        return task.call();
                    }))
                    .toList();
            startGun.countDown();
            return futures.stream().map(this::valueOf).toList();
        } finally {
            pool.shutdown();
        }
    }

    private <T> T valueOf(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
