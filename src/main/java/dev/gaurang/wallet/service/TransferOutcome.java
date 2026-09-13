package dev.gaurang.wallet.service;

import dev.gaurang.wallet.domain.Transfer;

/** replay=true means this request hit an already-committed transfer and moved no money. */
public record TransferOutcome(Transfer transfer, boolean replay) {
}
