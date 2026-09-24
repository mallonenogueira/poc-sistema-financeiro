package br.com.pocbank.account.domain.transfer;

import br.com.pocbank.account.domain.DomainException;

public class InvalidTransferException extends DomainException {

    public InvalidTransferException(String message) {
        super(message);
    }
}
