package br.com.pocbank.statement.messaging;

import br.com.pocbank.statement.application.StatementProjection;
import br.com.pocbank.statement.application.TransferCompleted;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferEventHandlerTest {

    @Mock
    private StatementProjection projection;

    private TransferEventHandler handler() {
        return new TransferEventHandler(JsonMapper.builder().findAndAddModules().build(), projection);
    }

    @Test
    void projectsTransferCompletedEvents() {
        when(projection.apply(any())).thenReturn(Mono.empty());
        String message = """
                {
                  "eventId": "e1",
                  "eventType": "TransferCompleted",
                  "occurredAt": "2026-09-23T12:00:00Z",
                  "data": {
                    "transferId": "t1",
                    "sourceAccountId": "a",
                    "targetAccountId": "b",
                    "amount": 150.00,
                    "fee": 0.00,
                    "type": "PIX",
                    "occurredAt": "2026-09-23T12:00:00Z",
                    "fieldAddedInV2": "ignored"
                  }
                }
                """;

        StepVerifier.create(handler().handle(message)).verifyComplete();

        ArgumentCaptor<TransferCompleted> event = ArgumentCaptor.forClass(TransferCompleted.class);
        verify(projection).apply(event.capture());
        assertThat(event.getValue().transferId()).isEqualTo("t1");
        assertThat(event.getValue().amount()).isEqualByComparingTo("150.00");
        assertThat(event.getValue().occurredAt()).isEqualTo(Instant.parse("2026-09-23T12:00:00Z"));
    }

    @Test
    void ignoresEventTypesItDoesNotCareAbout() {
        StepVerifier.create(handler().handle("""
                {"eventType":"TransferRejected","data":{}}
                """)).verifyComplete();

        verifyNoInteractions(projection);
    }

    @Test
    void discardsPoisonPillInsteadOfBlockingThePartition() {
        StepVerifier.create(handler().handle("not-json")).verifyComplete();
        StepVerifier.create(handler().handle("""
                {"eventType":"TransferCompleted","data":{"transferId":"t1"}}
                """)).verifyComplete();

        verifyNoInteractions(projection);
    }

    @Test
    void propagatesInfrastructureErrorsSoTheConsumerRetries() {
        when(projection.apply(any())).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(handler().handle("""
                {"eventType":"TransferCompleted","data":{"transferId":"t1","sourceAccountId":"a",
                 "targetAccountId":"b","amount":1,"fee":0,"type":"PIX","occurredAt":"2026-09-23T12:00:00Z"}}
                """)).verifyError(IllegalStateException.class);
    }
}
