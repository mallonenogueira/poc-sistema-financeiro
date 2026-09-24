package br.com.pocbank.statement.messaging;

import br.com.pocbank.statement.application.StatementProjection;
import br.com.pocbank.statement.application.TransferCompleted;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Roteia mensagens do envelope pelo {@code eventType}. Mensagens malformadas são
 * registradas e descartadas (em produção iriam para uma DLQ) para não travar a
 * partição (poison pill). Erros de infraestrutura propagam e disparam retry.
 */
@Component
public class TransferEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TransferEventHandler.class);

    private final ObjectMapper objectMapper;
    private final StatementProjection projection;

    public TransferEventHandler(ObjectMapper objectMapper, StatementProjection projection) {
        this.objectMapper = objectMapper;
        this.projection = projection;
    }

    public Mono<Void> handle(String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (JsonProcessingException e) {
            log.error("Mensagem inválida descartada: {}", e.getOriginalMessage());
            return Mono.empty();
        }

        String eventType = envelope.path("eventType").asText();
        if (!"TransferCompleted".equals(eventType)) {
            log.debug("Evento {} ignorado pelo extrato", eventType);
            return Mono.empty();
        }
        return parse(envelope.path("data"))
                .map(projection::apply)
                .orElseGet(Mono::empty);
    }

    private Optional<TransferCompleted> parse(JsonNode data) {
        try {
            TransferCompleted event = objectMapper.treeToValue(data, TransferCompleted.class);
            if (event == null || event.transferId() == null || event.amount() == null) {
                log.error("Payload TransferCompleted incompleto descartado: {}", data);
                return Optional.empty();
            }
            return Optional.of(event);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Payload TransferCompleted inválido descartado", e);
            return Optional.empty();
        }
    }
}
