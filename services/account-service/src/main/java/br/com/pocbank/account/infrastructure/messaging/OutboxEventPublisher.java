package br.com.pocbank.account.infrastructure.messaging;

import br.com.pocbank.account.application.port.DomainEventPublisher;
import br.com.pocbank.account.domain.event.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox: em vez de publicar direto no Kafka (dual write), grava o
 * evento numa tabela na mesma transação do negócio. O {@link OutboxRelay} publica depois.
 */
@Component
public class OutboxEventPublisher implements DomainEventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Clock clock;

    public OutboxEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper,
                                @Value("${app.kafka.topics.transfer-events}") String topic, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        EventEnvelope envelope = new EventEnvelope(event.eventId(), event.eventType(), event.occurredAt(), event);
        outbox.save(new OutboxEvent(event.eventId(), event.aggregateId().toString(), event.eventType(), topic,
                serialize(envelope), clock.instant()));
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar evento " + envelope.eventType(), e);
        }
    }

    /** Envelope versionável e agnóstico ao tipo de evento (contrato com consumidores). */
    public record EventEnvelope(UUID eventId, String eventType, Instant occurredAt, Object data) {
    }
}
