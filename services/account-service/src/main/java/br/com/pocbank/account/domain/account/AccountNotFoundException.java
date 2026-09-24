package br.com.pocbank.account.domain.account;

import br.com.pocbank.account.domain.DomainException;

import java.util.UUID;

public class AccountNotFoundException extends DomainException {

    public AccountNotFoundException(UUID accountId) {
        super("Conta %s não encontrada".formatted(accountId));
    }
}
