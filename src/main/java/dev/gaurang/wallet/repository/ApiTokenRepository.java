package dev.gaurang.wallet.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ApiTokenRepository {

    private final JdbcClient jdbc;

    public ApiTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String tokenHash, String userId) {
        jdbc.sql("INSERT INTO api_tokens (token_hash, user_id) VALUES (:hash, :userId)")
                .param("hash", tokenHash)
                .param("userId", userId)
                .update();
    }

    public Optional<String> findUserIdByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT user_id FROM api_tokens WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .query(String.class)
                .optional();
    }
}
