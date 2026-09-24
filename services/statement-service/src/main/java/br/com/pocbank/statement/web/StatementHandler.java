package br.com.pocbank.statement.web;

import br.com.pocbank.statement.application.StatementEventBus;
import br.com.pocbank.statement.application.StatementExportService;
import br.com.pocbank.statement.domain.StatementEntry;
import br.com.pocbank.statement.domain.StatementRepository;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class StatementHandler {

    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final StatementRepository repository;
    private final StatementEventBus bus;
    private final StatementExportService exportService;

    public StatementHandler(StatementRepository repository, StatementEventBus bus,
                            StatementExportService exportService) {
        this.repository = repository;
        this.bus = bus;
        this.exportService = exportService;
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        String accountId = request.pathVariable("accountId");
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(repository.findByAccountIdOrderByOccurredAtDesc(accountId), StatementEntry.class);
    }

    /** Server-Sent Events: o front recebe lançamentos em tempo real, com heartbeat contra timeouts de proxy. */
    public Mono<ServerResponse> stream(ServerRequest request) {
        String accountId = request.pathVariable("accountId");
        Flux<ServerSentEvent<StatementEntry>> entries = bus.stream(accountId)
                .map(entry -> ServerSentEvent.builder(entry).id(entry.id()).event("statement-entry").build());
        Flux<ServerSentEvent<StatementEntry>> heartbeat = Flux.interval(HEARTBEAT)
                .map(tick -> ServerSentEvent.<StatementEntry>builder().comment("keep-alive").build());
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(BodyInserters.fromServerSentEvents(Flux.merge(entries, heartbeat)));
    }

    public Mono<ServerResponse> export(ServerRequest request) {
        return exportService.export(request.pathVariable("accountId"))
                .flatMap(result -> ServerResponse.accepted().bodyValue(result));
    }
}
