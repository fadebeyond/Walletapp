package dev.gaurang.wallet.service;

import dev.gaurang.wallet.api.dto.InvariantsResponse;
import dev.gaurang.wallet.repository.InvariantsRepository;
import dev.gaurang.wallet.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets a load test assert conservation without shell access to the database. */
@Service
public class InvariantsService {

    private final InvariantsRepository invariantsRepository;
    private final LedgerRepository ledgerRepository;

    public InvariantsService(InvariantsRepository invariantsRepository, LedgerRepository ledgerRepository) {
        this.invariantsRepository = invariantsRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional(readOnly = true)
    public InvariantsResponse snapshot() {
        InvariantsRepository.Snapshot snapshot = invariantsRepository.snapshot();
        long ledgerSum = ledgerRepository.sumOfAllEntries();
        boolean conserved = ledgerSum == 0
                && snapshot.userBalanceTotalPaise() + snapshot.houseFloatBalancePaise() == 0
                && snapshot.negativeBalanceWallets() == 0;
        return new InvariantsResponse(snapshot.walletCount(), snapshot.userBalanceTotalPaise(),
                snapshot.houseFloatBalancePaise(), ledgerSum, snapshot.negativeBalanceWallets(), conserved);
    }
}
