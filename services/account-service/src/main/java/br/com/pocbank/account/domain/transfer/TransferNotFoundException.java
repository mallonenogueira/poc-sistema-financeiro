package br.com.pocbank.account.domain.transfer;

import br.com.pocbank.account.domain.DomainException;

import java.util.UUID;

public class TransferNotFoundException extends DomainException {

    public TransferNotFoundException(UUID transferId) {
        super("Transferência %s não encontrada".formatted(transferId));
    }
}
