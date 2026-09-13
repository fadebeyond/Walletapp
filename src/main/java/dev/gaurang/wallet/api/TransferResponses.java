package dev.gaurang.wallet.api;

import dev.gaurang.wallet.api.dto.TransferResponse;
import dev.gaurang.wallet.domain.TransferStatus;
import dev.gaurang.wallet.service.TransferOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

final class TransferResponses {

    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private TransferResponses() {
    }

    /** A replay repeats the original status and body; only the header says it did not move money again. */
    static ResponseEntity<TransferResponse> toResponseEntity(TransferOutcome outcome) {
        HttpStatus status = outcome.transfer().status() == TransferStatus.COMPLETED
                ? HttpStatus.CREATED
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .header(REPLAY_HEADER, Boolean.toString(outcome.replay()))
                .body(TransferResponse.from(outcome.transfer()));
    }
}
