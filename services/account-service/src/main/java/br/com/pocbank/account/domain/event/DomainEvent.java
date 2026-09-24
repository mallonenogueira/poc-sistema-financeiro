package br.com.pocbank.account.domain.event;

import java.time.Instant;
import java.util.UUID;

public sealed interface DomainEvent permits TransferCompletedEvent, TransferRejectedEvent {

    UUID eventId();

    UUID aggregateId();

    Instant occurredAt();

    /** Nome publicado no envelope/header Kafka, ex.: "TransferCompleted". */
    default String eventType() {
        return getClass().getSimpleName().replace("Event", "");
    }
}
