package br.com.pocbank.account.domain.account;

import br.com.pocbank.account.domain.DomainException;

import java.util.UUID;

public class AccountBlockedException extends DomainException {

    public AccountBlockedException(UUID accountId) {
        super("Conta %s está bloqueada".formatted(accountId));
    }
}
