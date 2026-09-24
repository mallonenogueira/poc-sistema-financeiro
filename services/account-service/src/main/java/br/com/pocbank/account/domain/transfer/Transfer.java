package br.com.pocbank.account.domain.transfer;

import br.com.pocbank.account.domain.Amounts;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro imutável de uma transferência. Transferências rejeitadas também são
 * persistidas para trilha de auditoria (exigência comum em ambiente regulado).
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {
        // JPA
    }

    private Transfer(Draft draft, TransferStatus status, String rejectionReason) {
        this.id = draft.id();
        this.sourceAccountId = draft.sourceAccountId();
        this.targetAccountId = draft.targetAccountId();
        this.amount = Amounts.normalize(draft.amount());
        this.fee = Amounts.normalize(draft.fee());
        this.type = draft.type();
        this.idempotencyKey = draft.idempotencyKey();
        this.createdAt = draft.createdAt();
        this.status = status;
        this.rejectionReason = rejectionReason;
    }

    public static Transfer completed(Draft draft) {
        return new Transfer(draft, TransferStatus.COMPLETED, null);
    }

    public static Transfer rejected(Draft draft, String reason) {
        return new Transfer(draft, TransferStatus.REJECTED, reason);
    }

    /** Parameter Object: evita um construtor com oito argumentos posicionais. */
    public record Draft(UUID id, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount,
                        BigDecimal fee, TransferType type, String idempotencyKey, Instant createdAt) {

        public BigDecimal totalDebited() {
            return amount.add(fee);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public TransferType getType() {
        return type;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
