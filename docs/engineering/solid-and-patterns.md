# SOLID, Clean Code e Design Patterns na POC

Um mapa para o revisor: cada princípio e padrão aparece num ponto concreto do código.

## SOLID

| Princípio | Onde | Como |
|---|---|---|
| **S**: Single Responsibility | `TransferService`, `OutboxEventPublisher`, `OutboxRelay` | O caso de uso orquestra, o publisher serializa e grava no outbox, o relay só entrega ao Kafka. Cada um muda por um motivo diferente. |
| **O**: Open/Closed | `FeePolicy` + `FeePolicyResolver` | Uma modalidade nova (ex.: TEF) é uma classe nova registrada como bean. `TransferService` não muda. O resolver falha no boot se faltar política. |
| **L**: Liskov | `DomainEvent` (sealed) | Qualquer evento pode ser publicado pelo `DomainEventPublisher` sem checagem de tipo. |
| **I**: Interface Segregation | `FraudCheckPort`, `DomainEventPublisher` | Portas pequenas e específicas do que o caso de uso precisa, em vez de um "IntegrationService" genérico. |
| **D**: Dependency Inversion | `application.port.*` | O caso de uso depende das portas, e as implementações (Feign, JPA outbox) ficam na infraestrutura. Por isso o `TransferServiceTest` roda só com Mockito, sem Spring. |

## Design Patterns

| Padrão | Onde | Motivo |
|---|---|---|
| Strategy | `FeePolicy` | Regra de tarifa varia por modalidade |
| Factory Method | `Account.open`, `Transfer.completed/rejected`, `TransferCompletedEvent.of` | Criação com invariantes e nomes que revelam intenção |
| Parameter Object | `Transfer.Draft` | Evita construtor com oito argumentos posicionais |
| Ports & Adapters | `FraudCheckPort` → `FraudCheckAdapter` | Isola o domínio de HTTP/Feign |
| Anti-Corruption Layer | `FraudCheckAdapter.toDecision` | O contrato externo não vaza para o domínio |
| Transactional Outbox | `outbox_events` + `OutboxRelay` | Atomicidade entre estado e evento ([ADR 0002](../adr/0002-transactional-outbox.md)) |
| Circuit Breaker / Timeout | Resilience4j em `fraud-api` | Evita falha em cascata |
| Observer (reativo) | `StatementEventBus` (Sinks) | Projeção notifica clientes SSE |
| CQRS | account-service (escrita) × statement-service (leitura) | Modelos otimizados por uso |
| Idempotent Receiver | IDs determinísticos no Mongo, `Idempotency-Key` na API | Entrega at-least-once e retries seguros |
| Facade | `frontend/src/design-system/index.ts` | DS substituível ([ADR 0005](../adr/0005-design-system-facade.md)) |
| Chain of Responsibility | `GlobalFilter` no Gateway, `OncePerRequestFilter` | Correlation-id transversal |

## Clean Code: convenções adotadas

- **Domínio rico**: `Account.debit` valida e altera o estado. Não há setters públicos nem "anemic model".
- **Nomes do negócio**: `settle`, `reject`, `lockInOrder`, `totalDebited`.
- **Dinheiro**: sempre `BigDecimal` com arredondamento central (`Amounts.normalize`, `HALF_EVEN`). No Mongo, `Decimal128`. Nunca `double`.
- **Erros**: exceções de domínio tipadas, traduzidas para HTTP numa camada única (RFC 9457).
- **Comentários explicam o porquê** (ex.: ordem dos locks, retry só em GET), não o quê.
- **Testes como documentação**: nomes descrevem comportamento (`insufficientFundsAbortsWithoutPublishing`).
- **Configuração externa** (12-factor): tudo via variável de ambiente com default local.
- **LGPD**: CPF mascarado na API e retenção limitada dos exports no S3.
