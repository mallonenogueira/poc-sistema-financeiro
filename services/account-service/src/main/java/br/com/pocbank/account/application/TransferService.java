package br.com.pocbank.account.application;

import br.com.pocbank.account.application.port.DomainEventPublisher;
import br.com.pocbank.account.application.port.FraudCheckPort;
import br.com.pocbank.account.application.port.FraudCheckPort.FraudCheckRequest;
import br.com.pocbank.account.application.port.FraudCheckPort.FraudDecision;
import br.com.pocbank.account.domain.account.Account;
import br.com.pocbank.account.domain.account.AccountNotFoundException;
import br.com.pocbank.account.domain.account.AccountRepository;
import br.com.pocbank.account.domain.event.TransferCompletedEvent;
import br.com.pocbank.account.domain.event.TransferRejectedEvent;
import br.com.pocbank.account.domain.fee.FeePolicyResolver;
import br.com.pocbank.account.domain.transfer.Transfer;
import br.com.pocbank.account.domain.transfer.TransferNotFoundException;
import br.com.pocbank.account.domain.transfer.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Caso de uso "transferir entre contas".
 *
 * <ul>
 *   <li>Idempotente por {@code Idempotency-Key}: repetições devolvem o mesmo resultado.</li>
 *   <li>A chamada ao antifraude (rede) acontece <b>fora</b> da transação de banco,
 *       para não segurar locks/conexões durante I/O remoto.</li>
 *   <li>Contas são travadas sempre na mesma ordem (por UUID) para evitar deadlock
 *       entre transferências cruzadas A→B e B→A.</li>
 *   <li>O evento é gravado no Outbox na mesma transação do débito/crédito.</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final FraudCheckPort fraudCheck;
    private final FeePolicyResolver feePolicies;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    public TransferService(AccountRepository accounts, TransferRepository transfers, FraudCheckPort fraudCheck,
                           FeePolicyResolver feePolicies, DomainEventPublisher events, TransactionOperations tx,
                           Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.fraudCheck = fraudCheck;
        this.feePolicies = feePolicies;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public Transfer execute(TransferCommand command) {
        return transfers.findByIdempotencyKey(command.idempotencyKey())
                .orElseGet(() -> process(command));
    }

    @Transactional(readOnly = true)
    public Transfer findById(UUID transferId) {
        return transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    private Transfer process(TransferCommand command) {
        ensureExists(command.sourceAccountId());
        ensureExists(command.targetAccountId());

        BigDecimal fee = feePolicies.resolve(command.type()).calculate(command.amount());
        Transfer.Draft draft = new Transfer.Draft(UUID.randomUUID(), command.sourceAccountId(),
                command.targetAccountId(), command.amount(), fee, command.type(), command.idempotencyKey(),
                clock.instant());

        FraudDecision decision = fraudCheck.evaluate(new FraudCheckRequest(draft.id(), draft.sourceAccountId(),
                draft.targetAccountId(), draft.amount(), draft.type()));

        try {
            return tx.execute(status -> decision.approved() ? settle(draft) : reject(draft, decision));
        } catch (DataIntegrityViolationException e) {
            // Requisição concorrente com a mesma Idempotency-Key venceu a corrida.
            log.info("Idempotency-Key {} já processada concorrentemente", command.idempotencyKey());
            return transfers.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
        }
    }

    private Transfer settle(Transfer.Draft draft) {
        Map<UUID, Account> locked = lockInOrder(draft.sourceAccountId(), draft.targetAccountId());
        locked.get(draft.sourceAccountId()).debit(draft.totalDebited());
        locked.get(draft.targetAccountId()).credit(draft.amount());

        Transfer transfer = transfers.saveAndFlush(Transfer.completed(draft));
        events.publish(TransferCompletedEvent.of(transfer));
        log.info("Transferência {} concluída: {} {}", transfer.getId(), transfer.getType(), transfer.getAmount());
        return transfer;
    }

    private Transfer reject(Transfer.Draft draft, FraudDecision decision) {
        Transfer transfer = transfers.saveAndFlush(Transfer.rejected(draft, decision.reason()));
        events.publish(TransferRejectedEvent.of(transfer));
        log.warn("Transferência {} rejeitada pelo antifraude (score={}): {}", transfer.getId(), decision.score(),
                decision.reason());
        return transfer;
    }

    private Map<UUID, Account> lockInOrder(UUID first, UUID second) {
        Map<UUID, Account> locked = new HashMap<>();
        Stream.of(first, second).sorted().forEach(id ->
                locked.put(id, accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id))));
        return locked;
    }

    private void ensureExists(UUID accountId) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }
}
