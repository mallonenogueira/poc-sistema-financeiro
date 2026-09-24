# Aula 10: Eventos, Transactional Outbox e Kafka

**Objetivo:** entender por que os serviços se comunicam por eventos, o problema do dual write, como o Outbox o resolve, os conceitos do Kafka e como um consumidor seguro é construído.

---

## 1. Por que eventos

Depois de uma transferência, várias coisas precisam acontecer: atualizar o extrato, notificar o cliente, alimentar o antifraude com histórico, contabilizar. Se o account-service chamasse cada um por HTTP:
- ele precisaria **conhecer** todos os interessados (acoplamento);
- se o serviço de notificação caísse, a transferência falharia (ou ficaria meio feita);
- cada novo interessado exigiria mudar o account-service.

Com eventos, o account-service **anuncia um fato** ("TransferCompleted") e segue a vida. Quem se interessa, assina.

| | Comando (síncrono) | Evento (assíncrono) |
|---|---|---|
| Semântica | "Faça isto" | "Isto aconteceu" |
| Quem conhece quem | O chamador conhece o destino | O produtor não conhece os consumidores |
| Falha do destino | Afeta o chamador | Não afeta o produtor |
| Resposta | Imediata | Nenhuma (fire and forget) |

## 2. O problema do dual write

```java
@Transactional
public void transferir(...) {
    contaRepository.save(...);                // 1. banco
    kafkaTemplate.send("transfer-events", e); // 2. Kafka
}
```
Dois sistemas, sem transação comum:
- **Kafka falha depois do commit** (ou a aplicação morre entre 1 e 2): o dinheiro se moveu e o evento nunca existiu. O extrato nunca mostra o lançamento.
- **`send` acontece e o commit falha depois** (constraint, deadlock): o evento foi publicado sobre algo que **não aconteceu**. O extrato mostra dinheiro fantasma.

Trocar a ordem não resolve; só troca qual dos dois erros acontece.

## 3. A solução: Transactional Outbox

```
┌──────────── mesma transação no Postgres ────────────┐
│ UPDATE accounts ...  (débito e crédito)             │
│ INSERT INTO transfers ...                           │
│ INSERT INTO outbox_events (payload do evento) ...   │
└─────────────────────── COMMIT ──────────────────────┘
                 │
      OutboxRelay (a cada 500 ms)
      SELECT pendentes FOR UPDATE SKIP LOCKED
      → envia ao Kafka → marca published_at
```
Ou tudo é gravado, ou nada. Se o Kafka estiver fora, os eventos **esperam na tabela** e saem quando ele voltar.

### As peças
[`OutboxEventPublisher`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxEventPublisher.java):
```java
@Transactional(propagation = Propagation.MANDATORY)   // exige a transação do chamador
public void publish(DomainEvent event) {
    EventEnvelope envelope = new EventEnvelope(event.eventId(), event.eventType(), event.occurredAt(), event);
    outbox.save(new OutboxEvent(..., serialize(envelope), ...));
}
```
[`OutboxRelay`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxRelay.java):
```java
@Scheduled(fixedDelayString = "${app.outbox.relay-interval-ms:1000}")
@Transactional
public void relay() {
    List<OutboxEvent> batch = outbox.lockNextBatch(BATCH_SIZE);
    for (OutboxEvent event : batch) {
        if (!send(event)) {
            return;              // primeira falha: para, para não publicar fora de ordem
        }
        event.markPublished(clock.instant());
    }
}
```

### Consequência inevitável: at-least-once
Cenário: o relay envia ao Kafka, o Kafka confirma, e a aplicação **morre antes do commit** que marca `published_at`. Na volta, o evento ainda está pendente e é enviado **de novo**.

> Com outbox, a entrega é **pelo menos uma vez**. "Exatamente uma vez" ponta a ponta não existe entre sistemas diferentes; o que existe é **at-least-once + consumidor idempotente**, que produz o **efeito** de exatamente uma vez.

### Trade-offs e alternativas
| | Polling (POC) | CDC (Debezium) |
|---|---|---|
| Como | Job consulta a tabela | Lê o log de replicação (WAL) do Postgres |
| Latência | Intervalo do polling (~500 ms) | Quase imediata |
| Carga no banco | Consultas periódicas | Mínima |
| Complexidade | Baixa, é só código | Mais uma peça (Kafka Connect) |

**Autocrítica:** o relay mantém a transação (e os locks das linhas) aberta **enquanto envia ao Kafka**, até 10 s por evento. Isso contradiz a regra "nada de I/O remoto com transação aberta" da [Aula 05](05-postgresql-transacoes-e-concorrencia.md). É um trade-off para usar o `SKIP LOCKED` de forma simples. Uma versão melhor: numa transação curta, "reservar" o lote (`UPDATE ... SET locked_until = now() + '30s'`); enviar fora de transação; marcar como publicado em outra transação curta. Outra lacuna: **não há limpeza** dos eventos publicados, e a tabela cresce para sempre.

## 4. Kafka: os conceitos

```
Tópico "transfer-events"
 ├── Partição 0: [off 0][off 1][off 2][off 3] ...  → consumida pela réplica A do statement-service
 ├── Partição 1: [off 0][off 1] ...                → réplica B
 └── Partição 2: [off 0][off 1][off 2] ...         → réplica A
```
| Conceito | O que é |
|---|---|
| **Tópico** | Um log nomeado de mensagens |
| **Partição** | Subdivisão do tópico. **A ordem só é garantida dentro de uma partição** |
| **Chave** | Define a partição (`hash(chave) % nº de partições`). Mesma chave, mesma partição, ordem garantida |
| **Offset** | Posição da mensagem na partição. O consumidor guarda até onde leu |
| **Consumer group** | Consumidores que dividem as partições entre si. Cada partição é lida por **um** membro do grupo |
| **Rebalance** | Redistribuição das partições quando um consumidor entra ou sai |
| **Retenção** | Mensagens **não são apagadas ao serem lidas**; ficam pelo tempo configurado (padrão 7 dias). Outro grupo pode ler tudo do início |
| **Replicação** | Cada partição é copiada em N brokers para tolerar falhas |

### Decisões da POC
- **Chave = `transferId`** (`aggregateId`): todos os eventos da mesma transferência ficam em ordem.
- **3 partições:** até 3 consumidores úteis no grupo. Por isso o comentário no K8s: "réplicas ≤ partições".
- **Produtor com `acks=all` + `enable.idempotence=true`:** a mensagem só é confirmada quando todas as réplicas em sincronia a têm, e retries internos do produtor não duplicam.

### Kafka × alternativas
| | Kafka | RabbitMQ | SQS/SNS | Postgres como fila |
|---|---|---|---|---|
| Modelo | Log retido, replay | Fila, mensagem some ao ser consumida | Fila gerenciada | Tabela + SKIP LOCKED |
| Vazão | Altíssima | Alta | Alta | Moderada |
| Replay | ✔ | ✘ | ✘ | Se você guardar |
| Operação | Pesada | Média | Zero | Zero (já existe) |
| Bom para | Streaming, vários consumidores, event sourcing | Filas de trabalho, roteamento complexo | Nativo AWS, simples | Volume moderado, sem infra nova |

## 5. O consumidor seguro

[`TransferEventsConsumer`](../../services/statement-service/src/main/java/br/com/pocbank/statement/messaging/TransferEventsConsumer.java):
```java
Flux.defer(() -> KafkaReceiver.create(options).receive())
    .concatMap(record -> handler.handle(record.value())
            .then(Mono.fromRunnable(() -> record.receiverOffset().acknowledge())))
    .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
    .subscribe();
```
| Decisão | Por quê |
|---|---|
| `enable.auto.commit=false` + `acknowledge()` **depois** de gravar | Se cair no meio, a mensagem é relida (at-least-once) em vez de perdida |
| `concatMap` (e não `flatMap`) | Processa uma por vez, na ordem da partição |
| `retryWhen` com backoff | Mongo fora do ar? Recria o consumidor e tenta de novo, sem martelar |
| `_id` determinístico na projeção | Reprocessar não duplica ([Aula 07](07-nosql-e-cqrs.md)) |

### Poison pill: a mensagem que nunca vai funcionar
Uma mensagem com JSON inválido falha sempre. Se o erro for tratado como "tenta de novo", o consumidor **trava nela para sempre** e a partição inteira para. [`TransferEventHandler`](../../services/statement-service/src/main/java/br/com/pocbank/statement/messaging/TransferEventHandler.java) separa:
- **Erro de dado** (JSON inválido, campo obrigatório faltando): loga e **descarta** (`Mono.empty()`), e o offset avança.
- **Erro de infraestrutura** (Mongo fora): **propaga**, e o retry acontece.

Descartar perde a mensagem. Em produção, ela iria para uma **DLQ** (Dead Letter Queue, um tópico `transfer-events.DLT`) para análise e reprocessamento manual.

## 6. Contrato de eventos e evolução

```json
{
  "eventId": "…", "eventType": "TransferCompleted", "occurredAt": "…",
  "data": { "transferId": "…", "amount": 150.00, ... }
}
```
- **Envelope comum:** o consumidor lê o `eventType` e decide se interessa, sem entender o `data` de todos os tipos.
- **Consumidor tolerante:** `@JsonIgnoreProperties(ignoreUnknown = true)`. O produtor pode **adicionar** campos sem quebrar ninguém.
- **Regra de evolução:** adicionar campo opcional é seguro. Remover, renomear ou mudar tipo quebra. Aí é preciso um evento novo (`TransferCompletedV2`) ou uma transição coordenada.
- **Em escala:** um **Schema Registry** (Avro/Protobuf/JSON Schema) valida a compatibilidade no momento da publicação e impede que um produtor quebre os consumidores.

---

## No seu ERP (Java + PostgreSQL)

**Você provavelmente não precisa de Kafka para ter os benefícios.** O outbox funciona com o que você já tem:

```sql
CREATE TABLE integracao.outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo VARCHAR(100) NOT NULL,          -- 'NotaAutorizada', 'PedidoFaturado'
    chave VARCHAR(100) NOT NULL,         -- id do agregado
    payload JSONB NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    tentativas INT NOT NULL DEFAULT 0,
    proxima_tentativa TIMESTAMPTZ NOT NULL DEFAULT now(),
    processado_em TIMESTAMPTZ,
    ultimo_erro TEXT
);
CREATE INDEX ON integracao.outbox (proxima_tentativa) WHERE processado_em IS NULL;
```
Worker (vários servidores podem rodar juntos):
```java
@Scheduled(fixedDelay = 1000)
public void processar() {
    List<Outbox> lote = tx.execute(s -> repo.reservarLote(50));  // UPDATE ... proxima_tentativa = now() + 5 min
    for (Outbox item : lote) {                                   // RETURNING, com SKIP LOCKED
        try {
            handlers.get(item.tipo()).enviar(item);              // fora de transação
            tx.executeWithoutResult(s -> repo.marcarProcessado(item.id()));
        } catch (Exception e) {
            tx.executeWithoutResult(s -> repo.agendarRetry(item.id(), backoff(item.tentativas()), e.getMessage()));
        }
    }
}
```
Casos de uso típicos:
- **Nota autorizada** → enviar e-mail com XML/DANFE, avisar o e-commerce, gerar título no financeiro.
- **Pedido faturado** → integração com transportadora, marketplace, CRM.
- **Qualquer integração** que hoje é chamada dentro da mesma transação do faturamento.

Para latência baixa sem polling: `LISTEN/NOTIFY` do Postgres acorda o worker na hora (`NOTIFY outbox_novo` num trigger), e o polling continua como rede de segurança.

**Quando o Kafka se justifica no ERP:** muitos sistemas consumindo os mesmos eventos, necessidade de replay, volume de milhões de eventos por dia, ou integração com um ecossistema que já usa Kafka.

---

## Exercícios

1. Pare o Kafka (`docker compose stop kafka`), faça 3 transferências, veja as linhas pendentes (`SELECT event_type, published_at FROM outbox_events ORDER BY created_at DESC LIMIT 5;`) e suba o Kafka de novo. O que acontece?
2. No Kafka UI, confirme que eventos com a mesma chave caíram na mesma partição.
3. Publique uma mensagem inválida no tópico pelo Kafka UI e veja o log do statement-service. O consumidor continuou funcionando?
4. Implemente a limpeza do outbox (apagar publicados há mais de 7 dias) como um job.
5. Refatore o `OutboxRelay` para não manter a transação aberta durante o envio.
6. No ERP, liste as integrações disparadas dentro da transação de faturamento. Quais poderiam ir para um outbox?
