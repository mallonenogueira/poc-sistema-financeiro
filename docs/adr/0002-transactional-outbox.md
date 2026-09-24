# ADR 0002: Transactional Outbox em vez de dual write

**Status:** aceito

## Contexto
Ao concluir uma transferência, precisamos (1) persistir o débito/crédito e (2) publicar `TransferCompleted` no Kafka. Fazer as duas coisas diretamente (*dual write*) abre duas janelas de inconsistência:
- o commit acontece e a publicação falha, e o extrato nunca recebe o lançamento;
- a publicação acontece e o commit falha, e o extrato mostra dinheiro que não se moveu.

## Decisão
- O evento é gravado na tabela `outbox_events` **na mesma transação** do negócio ([`OutboxEventPublisher`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxEventPublisher.java), `Propagation.MANDATORY`).
- O [`OutboxRelay`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxRelay.java) lê pendentes com `FOR UPDATE SKIP LOCKED` (várias réplicas drenam sem duplicar), publica com produtor idempotente (`acks=all`) e marca como publicado.
- Na primeira falha o lote para, preservando a ordem.
- Chave da mensagem = `transferId`. Header `eventType`. Payload em envelope `{eventId, eventType, occurredAt, data}`.

## Consequências
- ✅ Atomicidade entre estado e evento, sem transação distribuída (2PC).
- ⚠️ Entrega **at-least-once**, então consumidores precisam ser idempotentes. O statement-service usa IDs determinísticos (`transferId:DIREÇÃO`), e reprocessar vira upsert.
- ⚠️ Latência extra do polling (~500 ms). Em escala, dá para trocar o polling por CDC (Debezium) sem mudar o contrato.
- 🔧 Operação: criar um job de limpeza de eventos publicados antigos e um alerta para pendentes acima de N minutos.
