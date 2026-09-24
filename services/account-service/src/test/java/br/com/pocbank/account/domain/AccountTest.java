package br.com.pocbank.account.domain;

import br.com.pocbank.account.domain.account.Account;
import br.com.pocbank.account.domain.account.AccountBlockedException;
import br.com.pocbank.account.domain.account.InsufficientFundsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private final Account account = Account.open("Ana", "12345678901", new BigDecimal("100.00"), Instant.now());

    @Test
    void debitReducesBalance() {
        account.debit(new BigDecimal("30.50"));

        assertThat(account.getBalance()).isEqualByComparingTo("69.50");
    }

    @Test
    void creditIncreasesBalance() {
        account.credit(new BigDecimal("0.01"));

        assertThat(account.getBalance()).isEqualByComparingTo("100.01");
    }

    @Test
    void debitBeyondBalanceIsRejectedAndBalanceIsUntouched() {
        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void blockedAccountCannotMoveMoney() {
        account.block();

        assertThatThrownBy(() -> account.credit(BigDecimal.TEN)).isInstanceOf(AccountBlockedException.class);
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> account.debit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.credit(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotOpenWithNegativeDeposit() {
        assertThatThrownBy(() -> Account.open("Ana", "12345678901", new BigDecimal("-0.01"), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
