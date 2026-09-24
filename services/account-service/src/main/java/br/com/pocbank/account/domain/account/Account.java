package br.com.pocbank.account.domain.account;

import br.com.pocbank.account.domain.Amounts;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root de conta. Toda mutação de saldo passa por {@link #debit} e
 * {@link #credit}, que protegem as invariantes (saldo nunca negativo, conta ativa).
 * Não há setters públicos: o estado só muda por comportamento de negócio.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(nullable = false, unique = true, length = 11)
    private String document;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected Account() {
        // JPA
    }

    private Account(UUID id, String holderName, String document, BigDecimal balance, Instant createdAt) {
        this.id = id;
        this.holderName = holderName;
        this.document = document;
        this.balance = balance;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    public static Account open(String holderName, String document, BigDecimal initialDeposit, Instant now) {
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(document, "document");
        if (initialDeposit == null || initialDeposit.signum() < 0) {
            throw new IllegalArgumentException("Depósito inicial não pode ser negativo");
        }
        return new Account(UUID.randomUUID(), holderName.trim(), document, Amounts.normalize(initialDeposit), now);
    }

    public void debit(BigDecimal amount) {
        ensureActive();
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        balance = Amounts.normalize(balance.subtract(amount));
    }

    public void credit(BigDecimal amount) {
        ensureActive();
        requirePositive(amount);
        balance = Amounts.normalize(balance.add(amount));
    }

    public void block() {
        status = AccountStatus.BLOCKED;
    }

    private void ensureActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountBlockedException(id);
        }
    }

    private static void requirePositive(BigDecimal amount) {
        if (!Amounts.isPositive(amount)) {
            throw new IllegalArgumentException("Valor deve ser positivo");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getHolderName() {
        return holderName;
    }

    public String getDocument() {
        return document;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
