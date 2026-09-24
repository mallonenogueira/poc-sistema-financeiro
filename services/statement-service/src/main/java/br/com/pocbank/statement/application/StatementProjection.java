package br.com.pocbank.statement.application;

import br.com.pocbank.statement.domain.EntryDirection;
import br.com.pocbank.statement.domain.StatementEntry;
import br.com.pocbank.statement.domain.StatementRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

/** Projeta um TransferCompleted em dois lançamentos: débito na origem e crédito no destino. */
@Service
public class StatementProjection {

    private final StatementRepository repository;
    private final StatementEventBus bus;

    public StatementProjection(StatementRepository repository, StatementEventBus bus) {
        this.repository = repository;
        this.bus = bus;
    }

    public Mono<Void> apply(TransferCompleted event) {
        StatementEntry debit = new StatementEntry(
                StatementEntry.idFor(event.transferId(), EntryDirection.DEBIT),
                event.sourceAccountId(), event.transferId(), EntryDirection.DEBIT,
                event.amount().add(event.fee()), event.fee(), event.targetAccountId(), event.type(),
                event.occurredAt());
        StatementEntry credit = new StatementEntry(
                StatementEntry.idFor(event.transferId(), EntryDirection.CREDIT),
                event.targetAccountId(), event.transferId(), EntryDirection.CREDIT,
                event.amount(), BigDecimal.ZERO, event.sourceAccountId(), event.type(), event.occurredAt());

        return repository.saveAll(List.of(debit, credit))
                .doOnNext(bus::publish)
                .then();
    }
}
