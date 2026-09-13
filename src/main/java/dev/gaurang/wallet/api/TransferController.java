package dev.gaurang.wallet.api;

import dev.gaurang.wallet.api.dto.TransferRequest;
import dev.gaurang.wallet.api.dto.TransferResponse;
import dev.gaurang.wallet.domain.TransferKind;
import dev.gaurang.wallet.service.TransferCommand;
import dev.gaurang.wallet.service.TransferOutcome;
import dev.gaurang.wallet.service.TransferService;
import dev.gaurang.wallet.web.AuthenticationFilter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest request,
                                                   @RequestAttribute(AuthenticationFilter.USER_ID_ATTRIBUTE) String userId) {
        TransferCommand command = new TransferCommand(TransferKind.TRANSFER, userId, request.idempotencyKey(),
                request.from(), request.to(), request.amountPaise());
        return TransferResponses.toResponseEntity(transferService.settle(command));
    }

    @GetMapping("/{transferId}")
    public TransferResponse status(@PathVariable UUID transferId,
                                   @RequestAttribute(AuthenticationFilter.USER_ID_ATTRIBUTE) String userId) {
        return TransferResponse.from(transferService.getVisibleTransfer(transferId, userId));
    }
}
