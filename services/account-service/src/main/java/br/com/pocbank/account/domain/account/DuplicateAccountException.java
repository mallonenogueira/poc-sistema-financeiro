package br.com.pocbank.account.domain.account;

import br.com.pocbank.account.domain.DomainException;

public class DuplicateAccountException extends DomainException {

    public DuplicateAccountException() {
        super("Já existe uma conta para este documento");
    }
}
