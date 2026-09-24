package br.com.pocbank.statement.application;

import br.com.pocbank.statement.domain.EntryDirection;
import br.com.pocbank.statement.domain.StatementEntry;
import br.com.pocbank.statement.domain.StatementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementProjectionTest {

    @Mock
    private StatementRepository repository;

    private final StatementEventBus bus = new StatementEventBus();

    private final TransferCompleted event = new TransferCompleted("t1", "ana", "bruno",
            new BigDecimal("100.00"), new BigDecimal("5.10"), "TED", Instant.parse("2026-09-23T12:00:00Z"));

    @Test
    void createsDebitWithFeeAndCreditWithDeterministicIds() {
        when(repository.saveAll(ArgumentMatchers.<Iterable<StatementEntry>>any())).thenAnswer(invocation -> Flux.fromIterable(invocation.getArgument(0)));

        StepVerifier.create(bus.stream("ana").take(1))
                .then(() -> new StatementProjection(repository, bus).apply(event).subscribe())
                .assertNext(debit -> {
                    assertThat(debit.id()).isEqualTo("t1:DEBIT");
                    assertThat(debit.direction()).isEqualTo(EntryDirection.DEBIT);
                    assertThat(debit.amount()).isEqualByComparingTo("105.10");
                    assertThat(debit.counterpartyAccountId()).isEqualTo("bruno");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void notifiesLiveSubscribersOfTheCreditedAccount() {
        when(repository.saveAll(ArgumentMatchers.<Iterable<StatementEntry>>any())).thenAnswer(invocation -> Flux.fromIterable(invocation.getArgument(0)));

        StepVerifier.create(bus.stream("bruno").take(1))
                .then(() -> new StatementProjection(repository, bus).apply(event).subscribe())
                .assertNext(credit -> {
                    assertThat(credit.id()).isEqualTo("t1:CREDIT");
                    assertThat(credit.amount()).isEqualByComparingTo("100.00");
                    assertThat(credit.signedAmount()).isPositive();
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }
}
