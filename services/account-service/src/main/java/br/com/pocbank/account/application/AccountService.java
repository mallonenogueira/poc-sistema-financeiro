package br.com.pocbank.account.application;

import br.com.pocbank.account.domain.account.Account;
import br.com.pocbank.account.domain.account.AccountNotFoundException;
import br.com.pocbank.account.domain.account.AccountRepository;
import br.com.pocbank.account.domain.account.DuplicateAccountException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final Clock clock;

    public AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public Account open(String holderName, String document, BigDecimal initialDeposit) {
        if (accounts.existsByDocument(document)) {
            throw new DuplicateAccountException();
        }
        return accounts.save(Account.open(holderName, document, initialDeposit, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAll(Sort.by("createdAt"));
    }

    @Transactional(readOnly = true)
    public Account find(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
