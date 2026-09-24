package br.com.pocbank.account.domain.event;

import br.com.pocbank.account.domain.transfer.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferRejectedEvent(UUID eventId, UUID transferId, UUID sourceAccountId, BigDecimal amount,
                                    String reason, Instant occurredAt) implements DomainEvent {

    public static TransferRejectedEvent of(Transfer transfer) {
        return new TransferRejectedEvent(UUID.randomUUID(), transfer.getId(), transfer.getSourceAccountId(),
                transfer.getAmount(), transfer.getRejectionReason(), transfer.getCreatedAt());
    }

    @Override
    public UUID aggregateId() {
        return transferId;
    }
}
