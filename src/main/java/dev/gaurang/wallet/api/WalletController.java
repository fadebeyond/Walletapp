package dev.gaurang.wallet.api;

import dev.gaurang.wallet.api.dto.TopUpRequest;
import dev.gaurang.wallet.api.dto.TransferResponse;
import dev.gaurang.wallet.api.dto.WalletResponse;
import dev.gaurang.wallet.domain.TransferKind;
import dev.gaurang.wallet.repository.WalletRepository;
import dev.gaurang.wallet.service.TransferCommand;
import dev.gaurang.wallet.service.TransferOutcome;
import dev.gaurang.wallet.service.TransferService;
import dev.gaurang.wallet.service.WalletService;
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
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;
    private final TransferService transferService;

    public WalletController(WalletService walletService, TransferService transferService) {
        this.walletService = walletService;
        this.transferService = transferService;
    }

    @PostMapping
    public WalletResponse getOrCreate(@RequestAttribute(AuthenticationFilter.USER_ID_ATTRIBUTE) String userId) {
        return WalletResponse.from(walletService.getOrCreate(userId));
    }

    @GetMapping("/{walletId}")
    public WalletResponse balance(@PathVariable UUID walletId,
                                  @RequestAttribute(AuthenticationFilter.USER_ID_ATTRIBUTE) String userId) {
        return WalletResponse.from(walletService.getOwnedWallet(walletId, userId));
    }

    /** Funding rail stand-in: the same ledger primitive, debiting the house float wallet. */
    @PostMapping("/{walletId}/topups")
    public ResponseEntity<TransferResponse> topUp(@PathVariable UUID walletId,
                                                  @Valid @RequestBody TopUpRequest request,
                                                  @RequestAttribute(AuthenticationFilter.USER_ID_ATTRIBUTE) String userId) {
        TransferCommand command = new TransferCommand(TransferKind.TOPUP, userId, request.idempotencyKey(),
                WalletRepository.HOUSE_FLOAT_WALLET_ID, walletId, request.amountPaise());
        TransferOutcome outcome = transferService.settle(command);
        return TransferResponses.toResponseEntity(outcome);
    }
}
