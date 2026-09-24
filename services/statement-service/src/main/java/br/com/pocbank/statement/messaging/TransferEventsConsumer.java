package br.com.pocbank.statement.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Consumidor reativo (Reactor Kafka) com backpressure ponta a ponta.
 * <ul>
 *   <li>{@code concatMap} preserva a ordem dentro da partição.</li>
 *   <li>O offset só é confirmado depois que a projeção gravou no MongoDB (at-least-once).</li>
 *   <li>Falhas de infraestrutura recriam o receiver com backoff exponencial.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class TransferEventsConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsConsumer.class);

    private final ReceiverOptions<String, String> options;
    private final TransferEventHandler handler;
    private volatile Disposable subscription;

    public TransferEventsConsumer(ReceiverOptions<String, String> options, TransferEventHandler handler) {
        this.options = options;
        this.handler = handler;
    }

    @Override
    public void start() {
        subscription = Flux.defer(() -> KafkaReceiver.create(options).receive())
                .concatMap(record -> handler.handle(record.value())
                        .then(Mono.fromRunnable(() -> record.receiverOffset().acknowledge())))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.warn("Consumidor Kafka reiniciando (tentativa {})",
                                signal.totalRetries() + 1, signal.failure())))
                .subscribe();
        log.info("Consumidor de transfer-events iniciado");
    }

    @Override
    public void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }
}
