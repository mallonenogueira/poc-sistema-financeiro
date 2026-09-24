package br.com.pocbank.statement.application;

import br.com.pocbank.statement.domain.StatementEntry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;

/**
 * Observer reativo: a projeção emite novos lançamentos e os clientes SSE assinam
 * o fluxo filtrado pela sua conta. {@code directBestEffort} descarta para
 * assinantes lentos em vez de bloquear o consumo do Kafka (backpressure).
 */
@Component
public class StatementEventBus {

    private final Sinks.Many<StatementEntry> sink = Sinks.many().multicast().directBestEffort();

    public void publish(StatementEntry entry) {
        sink.emitNext(entry, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
    }

    public Flux<StatementEntry> stream(String accountId) {
        return sink.asFlux().filter(entry -> entry.accountId().equals(accountId));
    }
}
