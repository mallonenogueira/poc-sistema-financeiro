package br.com.pocbank.account.domain.event;

import br.com.pocbank.account.domain.transfer.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(UUID eventId, UUID transferId, UUID sourceAccountId, UUID targetAccountId,
                                     BigDecimal amount, BigDecimal fee, String type, Instant occurredAt)
        implements DomainEvent {

    public static TransferCompletedEvent of(Transfer transfer) {
        return new TransferCompletedEvent(UUID.randomUUID(), transfer.getId(), transfer.getSourceAccountId(),
                transfer.getTargetAccountId(), transfer.getAmount(), transfer.getFee(), transfer.getType().name(),
                transfer.getCreatedAt());
    }

    @Override
    public UUID aggregateId() {
        return transferId;
    }
}
