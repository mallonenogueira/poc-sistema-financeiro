package br.com.pocbank.account.application;

import br.com.pocbank.account.application.port.DomainEventPublisher;
import br.com.pocbank.account.application.port.FraudCheckPort;
import br.com.pocbank.account.application.port.FraudCheckPort.FraudDecision;
import br.com.pocbank.account.application.port.FraudServiceUnavailableException;
import br.com.pocbank.account.domain.account.Account;
import br.com.pocbank.account.domain.account.AccountNotFoundException;
import br.com.pocbank.account.domain.account.AccountRepository;
import br.com.pocbank.account.domain.account.InsufficientFundsException;
import br.com.pocbank.account.domain.event.TransferCompletedEvent;
import br.com.pocbank.account.domain.event.TransferRejectedEvent;
import br.com.pocbank.account.domain.fee.FeePolicyResolver;
import br.com.pocbank.account.domain.fee.PixFeePolicy;
import br.com.pocbank.account.domain.fee.TedFeePolicy;
import br.com.pocbank.account.domain.transfer.InvalidTransferException;
import br.com.pocbank.account.domain.transfer.Transfer;
import br.com.pocbank.account.domain.transfer.TransferRepository;
import br.com.pocbank.account.domain.transfer.TransferStatus;
import br.com.pocbank.account.domain.transfer.TransferType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Mock
    private AccountRepository accounts;
    @Mock
    private TransferRepository transfers;
    @Mock
    private FraudCheckPort fraudCheck;
    @Mock
    private DomainEventPublisher events;

    private TransferService service;
    private Account ana;
    private Account bruno;

    @BeforeEach
    void setUp() {
        service = new TransferService(accounts, transfers, fraudCheck,
                new FeePolicyResolver(List.of(new PixFeePolicy(), new TedFeePolicy())), events,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

        ana = Account.open("Ana", "12345678901", new BigDecimal("1000.00"), NOW);
        bruno = Account.open("Bruno", "98765432100", new BigDecimal("100.00"), NOW);

        lenient().when(transfers.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        lenient().when(transfers.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(accounts.existsById(any())).thenReturn(true);
        lenient().when(accounts.findByIdForUpdate(ana.getId())).thenReturn(Optional.of(ana));
        lenient().when(accounts.findByIdForUpdate(bruno.getId())).thenReturn(Optional.of(bruno));
    }

    private TransferCommand command(String amount, TransferType type) {
        return new TransferCommand(ana.getId(), bruno.getId(), new BigDecimal(amount), type, "key-1");
    }

    @Nested
    class WhenFraudApproves {

        @BeforeEach
        void approve() {
            when(fraudCheck.evaluate(any())).thenReturn(FraudDecision.approve(10));
        }

        @Test
        void movesMoneyAndChargesFee() {
            Transfer transfer = service.execute(command("100.00", TransferType.TED));

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(transfer.getFee()).isEqualByComparingTo("5.10");
            assertThat(ana.getBalance()).isEqualByComparingTo("894.90");
            assertThat(bruno.getBalance()).isEqualByComparingTo("200.00");
        }

        @Test
        void publishesCompletedEventAfterPersisting() {
            Transfer transfer = service.execute(command("50.00", TransferType.PIX));

            InOrder order = inOrder(transfers, events);
            order.verify(transfers).saveAndFlush(any());
            ArgumentCaptor<TransferCompletedEvent> event = ArgumentCaptor.forClass(TransferCompletedEvent.class);
            order.verify(events).publish(event.capture());
            assertThat(event.getValue().transferId()).isEqualTo(transfer.getId());
            assertThat(event.getValue().amount()).isEqualByComparingTo("50.00");
        }

        @Test
        void locksAccountsInDeterministicOrderToAvoidDeadlocks() {
            service.execute(command("10.00", TransferType.PIX));

            UUID first = ana.getId().compareTo(bruno.getId()) < 0 ? ana.getId() : bruno.getId();
            UUID second = first.equals(ana.getId()) ? bruno.getId() : ana.getId();
            InOrder order = inOrder(accounts);
            order.verify(accounts).findByIdForUpdate(first);
            order.verify(accounts).findByIdForUpdate(second);
        }

        @Test
        void insufficientFundsAbortsWithoutPublishing() {
            assertThatThrownBy(() -> service.execute(command("5000.00", TransferType.PIX)))
                    .isInstanceOf(InsufficientFundsException.class);

            verify(events, never()).publish(any());
        }
    }

    @Test
    void fraudDenialPersistsRejectedTransferWithoutMovingMoney() {
        when(fraudCheck.evaluate(any())).thenReturn(FraudDecision.deny(95, "Valor atípico"));

        Transfer transfer = service.execute(command("100.00", TransferType.PIX));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getRejectionReason()).isEqualTo("Valor atípico");
        assertThat(ana.getBalance()).isEqualByComparingTo("1000.00");
        verify(accounts, never()).findByIdForUpdate(any());
        verify(events).publish(any(TransferRejectedEvent.class));
    }

    @Test
    void repeatedIdempotencyKeyReturnsOriginalTransferWithoutReprocessing() {
        Transfer original = Transfer.completed(new Transfer.Draft(UUID.randomUUID(), ana.getId(), bruno.getId(),
                BigDecimal.TEN, BigDecimal.ZERO, TransferType.PIX, "key-1", NOW));
        when(transfers.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));

        Transfer result = service.execute(command("10.00", TransferType.PIX));

        assertThat(result).isSameAs(original);
        verifyNoInteractions(fraudCheck, events);
    }

    @Test
    void unknownAccountFailsBeforeCallingFraudService() {
        when(accounts.existsById(bruno.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.execute(command("10.00", TransferType.PIX)))
                .isInstanceOf(AccountNotFoundException.class);
        verifyNoInteractions(fraudCheck);
    }

    @Test
    void fraudServiceOutageIsPropagatedAndNothingIsPersisted() {
        when(fraudCheck.evaluate(any())).thenThrow(new FraudServiceUnavailableException(new RuntimeException()));

        assertThatThrownBy(() -> service.execute(command("10.00", TransferType.PIX)))
                .isInstanceOf(FraudServiceUnavailableException.class);
        verify(transfers, never()).saveAndFlush(any());
    }

    @Test
    void sameSourceAndTargetIsInvalid() {
        assertThatThrownBy(() ->
                new TransferCommand(ana.getId(), ana.getId(), BigDecimal.TEN, TransferType.PIX, "k"))
                .isInstanceOf(InvalidTransferException.class);
    }
}
