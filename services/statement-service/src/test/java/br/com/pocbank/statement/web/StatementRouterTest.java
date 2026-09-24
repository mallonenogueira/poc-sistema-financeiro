package br.com.pocbank.statement.web;

import br.com.pocbank.statement.application.StatementEventBus;
import br.com.pocbank.statement.application.StatementExportService;
import br.com.pocbank.statement.application.StatementExportService.ExportResult;
import br.com.pocbank.statement.domain.EntryDirection;
import br.com.pocbank.statement.domain.StatementEntry;
import br.com.pocbank.statement.domain.StatementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementRouterTest {

    @Mock
    private StatementRepository repository;
    @Mock
    private StatementExportService exportService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        StatementHandler handler = new StatementHandler(repository, new StatementEventBus(), exportService);
        client = WebTestClient.bindToRouterFunction(new StatementRouter().statementRoutes(handler)).build();
    }

    @Test
    void listsEntriesOfAnAccount() {
        when(repository.findByAccountIdOrderByOccurredAtDesc("ana")).thenReturn(Flux.just(
                new StatementEntry("t1:DEBIT", "ana", "t1", EntryDirection.DEBIT, new BigDecimal("10.00"),
                        BigDecimal.ZERO, "bruno", "PIX", Instant.parse("2026-09-23T12:00:00Z"))));

        client.get().uri("/api/statements/ana")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("t1:DEBIT")
                .jsonPath("$[0].direction").isEqualTo("DEBIT");
    }

    @Test
    void exportReturnsAcceptedWithS3Location() {
        when(exportService.export("ana"))
                .thenReturn(Mono.just(new ExportResult("pocbank-statements", "exports/ana/1.csv", 3)));

        client.post().uri("/api/statements/ana/exports")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.key").isEqualTo("exports/ana/1.csv")
                .jsonPath("$.entries").isEqualTo(3);
    }
}
