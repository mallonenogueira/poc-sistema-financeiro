package br.com.pocbank.account.infrastructure.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drena o outbox para o Kafka com semântica at-least-once. Consumidores devem ser
 * idempotentes (o statement-service usa IDs determinísticos para isso).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outbox.lockNextBatch(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            if (!send(event)) {
                return; // preserva a ordem: tenta de novo no próximo ciclo
            }
            event.markPublished(clock.instant());
        }
        if (!batch.isEmpty()) {
            log.debug("Outbox: {} evento(s) publicados", batch.size());
        }
    }

    private boolean send(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getAggregateId(), event.getPayload());
        record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Falha ao publicar evento {} no Kafka; nova tentativa no próximo ciclo", event.getId(), e);
            return false;
        }
    }
}
