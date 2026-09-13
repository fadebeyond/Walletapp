package dev.gaurang.wallet.service;

import dev.gaurang.wallet.repository.ApiTokenRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Stand-in for a real identity provider: it issues opaque bearer tokens and stores only their hash.
 * Issuing a token does not invalidate earlier ones.
 */
@Service
public class AuthService {

    private static final int TOKEN_BYTES = 32;

    private final ApiTokenRepository apiTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(ApiTokenRepository apiTokenRepository) {
        this.apiTokenRepository = apiTokenRepository;
    }

    public String issueToken(String userId) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        apiTokenRepository.save(sha256(token), userId);
        return token;
    }

    public Optional<String> resolveUserId(String token) {
        return apiTokenRepository.findUserIdByTokenHash(sha256(token));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
