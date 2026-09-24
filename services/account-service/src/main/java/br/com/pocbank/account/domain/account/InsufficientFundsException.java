package br.com.pocbank.account.domain.account;

import br.com.pocbank.account.domain.DomainException;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal requested) {
        super("Saldo insuficiente na conta %s: disponível %s, solicitado %s".formatted(accountId, balance, requested));
    }
}
