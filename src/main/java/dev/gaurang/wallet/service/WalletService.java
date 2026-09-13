package dev.gaurang.wallet.service;

import dev.gaurang.wallet.domain.Wallet;
import dev.gaurang.wallet.metrics.WalletMetrics;
import dev.gaurang.wallet.repository.Upserted;
import dev.gaurang.wallet.repository.WalletRepository;
import dev.gaurang.wallet.web.ApiException;
import dev.gaurang.wallet.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WalletMetrics metrics;

    public WalletService(WalletRepository walletRepository, WalletMetrics metrics) {
        this.walletRepository = walletRepository;
        this.metrics = metrics;
    }

    @Transactional
    public Wallet getOrCreate(String userId) {
        Upserted<Wallet> result = walletRepository.getOrCreate(userId, UUID.randomUUID());
        if (result.inserted()) {
            metrics.walletCreated();
            log.info("wallet.created", kv("event", "wallet.created"),
                    kv("wallet_id", result.row().id()), kv("user_id", userId));
        } else {
            metrics.walletReused();
            log.info("wallet.reused", kv("event", "wallet.reused"),
                    kv("wallet_id", result.row().id()), kv("user_id", userId));
        }
        return result.row();
    }

    @Transactional(readOnly = true)
    public Wallet getOwnedWallet(UUID walletId, String callerUserId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + walletId + " not found"));
        if (!wallet.isOwnedBy(callerUserId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Wallet " + walletId + " belongs to another user");
        }
        return wallet;
    }
}
