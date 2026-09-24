package br.com.pocbank.account.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Mock
    private OutboxRepository outbox;
    @Mock
    private KafkaTemplate<String, String> kafka;

    private OutboxRelay relay() {
        return new OutboxRelay(outbox, kafka, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent event(String aggregateId) {
        return new OutboxEvent(UUID.randomUUID(), aggregateId, "TransferCompleted", "transfer-events",
                "{\"eventType\":\"TransferCompleted\"}", NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesPendingEventsKeyedByAggregateAndMarksThemPublished() {
        OutboxEvent event = event("transfer-1");
        when(outbox.lockNextBatch(100)).thenReturn(List.of(event));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        relay().relay();

        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(record.capture());
        assertThat(record.getValue().key()).isEqualTo("transfer-1");
        assertThat(record.getValue().topic()).isEqualTo("transfer-events");
        assertThat(new String(record.getValue().headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("TransferCompleted");
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsAtFirstFailureToPreserveOrdering() {
        OutboxEvent first = event("transfer-1");
        OutboxEvent second = event("transfer-2");
        when(outbox.lockNextBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay().relay();

        verify(kafka, times(1)).send(any(ProducerRecord.class));
        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
    }
}
