package dev.gaurang.wallet.service;

import dev.gaurang.wallet.domain.MoneyMovement;
import dev.gaurang.wallet.domain.Transfer;
import dev.gaurang.wallet.domain.TransferKind;
import dev.gaurang.wallet.domain.TransferStatus;
import dev.gaurang.wallet.domain.Wallet;
import dev.gaurang.wallet.metrics.WalletMetrics;
import dev.gaurang.wallet.repository.LedgerRepository;
import dev.gaurang.wallet.repository.TransferRepository;
import dev.gaurang.wallet.repository.Upserted;
import dev.gaurang.wallet.repository.WalletRepository;
import dev.gaurang.wallet.web.ApiException;
import dev.gaurang.wallet.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final LedgerRepository ledgerRepository;
    private final WalletMetrics metrics;

    public TransferService(WalletRepository walletRepository, TransferRepository transferRepository,
                           LedgerRepository ledgerRepository, WalletMetrics metrics) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.ledgerRepository = ledgerRepository;
        this.metrics = metrics;
    }

    /**
     * The whole money path runs in one transaction: claiming the idempotency key, the conditional debit,
     * the credit, both ledger legs and the final status either all commit or none of them do.
     */
    @Transactional
    public TransferOutcome settle(TransferCommand command) {
        rejectSelfTransfer(command);
        MoneyMovement movement =
                new MoneyMovement(command.fromWalletId(), command.toWalletId(), command.amountPaise());

        // Locks first, in sorted order. The foreign keys on transfers take a KEY SHARE lock on both
        // wallet rows, so claiming the key before locking lets A->B and B->A deadlock on each other.
        Map<UUID, Wallet> locked = lockInOrder(movement);
        authorize(command, locked);

        Upserted<Transfer> claim = transferRepository.claimIdempotencyKey(candidateFor(command));
        if (!claim.inserted()) {
            return replayOf(claim.row(), command);
        }

        Transfer transfer = claim.row();
        log.info("transfer.created", kv("event", "transfer.created"), kv("transfer_id", transfer.id()),
                kv("kind", transfer.kind()), kv("from_wallet_id", transfer.fromWalletId()),
                kv("to_wallet_id", transfer.toWalletId()), kv("amount_paise", transfer.amountPaise()));
        metrics.transferCreated(transfer.kind());

        return apply(transfer, movement);
    }

    @Transactional(readOnly = true)
    public Transfer getVisibleTransfer(UUID transferId, String callerUserId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new ApiException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer " + transferId + " not found"));
        boolean visible = walletRepository.findAllByIds(List.of(transfer.fromWalletId(), transfer.toWalletId()))
                .stream().anyMatch(wallet -> wallet.isOwnedBy(callerUserId));
        if (!visible) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Transfer " + transferId + " involves no wallet of yours");
        }
        return transfer;
    }

    private TransferOutcome apply(Transfer transfer, MoneyMovement movement) {
        if (!walletRepository.debit(movement.debitWalletId(), movement.amountPaise())) {
            Transfer declined = transferRepository.settle(transfer.id(), TransferStatus.DECLINED_INSUFFICIENT_FUNDS);
            metrics.transferDeclinedForInsufficientFunds();
            log.info("transfer.declined", kv("event", "transfer.declined"), kv("transfer_id", declined.id()),
                    kv("reason", "insufficient_funds"), kv("from_wallet_id", declined.fromWalletId()),
                    kv("amount_paise", declined.amountPaise()));
            return new TransferOutcome(declined, false);
        }
        log.info("transfer.debited", kv("event", "transfer.debited"), kv("transfer_id", transfer.id()),
                kv("wallet_id", movement.debitWalletId()), kv("amount_paise", movement.amountPaise()));

        walletRepository.credit(movement.creditWalletId(), movement.amountPaise());
        log.info("transfer.credited", kv("event", "transfer.credited"), kv("transfer_id", transfer.id()),
                kv("wallet_id", movement.creditWalletId()), kv("amount_paise", movement.amountPaise()));

        ledgerRepository.recordDoubleEntry(transfer.id(), movement.debitWalletId(),
                movement.creditWalletId(), movement.amountPaise());

        Transfer completed = transferRepository.settle(transfer.id(), TransferStatus.COMPLETED);
        metrics.transferCompleted(completed.kind());
        log.info("transfer.completed", kv("event", "transfer.completed"), kv("transfer_id", completed.id()),
                kv("amount_paise", completed.amountPaise()));
        return new TransferOutcome(completed, false);
    }

    private TransferOutcome replayOf(Transfer existing, TransferCommand command) {
        if (!existing.requestFingerprint().equals(fingerprintOf(command))) {
            metrics.idempotencyConflict();
            log.warn("transfer.idempotency_conflict", kv("event", "transfer.idempotency_conflict"),
                    kv("transfer_id", existing.id()), kv("idempotency_key", command.idempotencyKey()));
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "Idempotency key '" + command.idempotencyKey() + "' was already used with a different request body");
        }
        metrics.idempotentReplay();
        log.info("transfer.idempotent_replay", kv("event", "transfer.idempotent_replay"),
                kv("transfer_id", existing.id()), kv("status", existing.status()));
        return new TransferOutcome(existing, true);
    }

    /** Always lower wallet id first, so two transfers touching the same pair queue instead of deadlocking. */
    private Map<UUID, Wallet> lockInOrder(MoneyMovement movement) {
        Wallet first = lockOrFail(movement.firstLockTarget());
        Wallet second = lockOrFail(movement.secondLockTarget());
        return Map.of(first.id(), first, second.id(), second);
    }

    private Wallet lockOrFail(UUID walletId) {
        return walletRepository.lockForUpdate(walletId)
                .orElseThrow(() -> new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + walletId + " not found"));
    }

    /** One rule everywhere: you may only move money out of a wallet you own. Top-ups fund your own wallet. */
    private void authorize(TransferCommand command, Map<UUID, Wallet> wallets) {
        UUID ownedWalletId = command.kind() == TransferKind.TOPUP ? command.toWalletId() : command.fromWalletId();
        if (!wallets.get(ownedWalletId).isOwnedBy(command.callerUserId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Wallet " + ownedWalletId + " belongs to another user");
        }
    }

    private void rejectSelfTransfer(TransferCommand command) {
        if (command.fromWalletId().equals(command.toWalletId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "from and to must be different wallets");
        }
    }

    private Transfer candidateFor(TransferCommand command) {
        return new Transfer(UUID.randomUUID(), command.kind(), command.callerUserId(), command.idempotencyKey(),
                fingerprintOf(command), command.fromWalletId(), command.toWalletId(), command.amountPaise(),
                TransferStatus.PENDING, null, null);
    }

    /** Hashing the business fields lets a replay be compared without storing the raw request. */
    private String fingerprintOf(TransferCommand command) {
        String canonical = String.join("|", command.kind().name(), command.fromWalletId().toString(),
                command.toWalletId().toString(), Long.toString(command.amountPaise()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
