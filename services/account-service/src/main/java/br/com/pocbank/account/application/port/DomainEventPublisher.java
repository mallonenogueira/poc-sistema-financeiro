package br.com.pocbank.account.application.port;

import br.com.pocbank.account.domain.event.DomainEvent;

/**
 * Publica eventos de domínio. A implementação grava no Outbox dentro da mesma
 * transação do caso de uso, garantindo atomicidade entre estado e evento.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
