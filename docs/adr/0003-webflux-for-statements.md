# ADR 0003: WebFlux + MongoDB reativo para o extrato (CQRS)

**Status:** aceito

## Contexto
O extrato tem muitas leituras, conexões longas (SSE) para atualização em tempo real e consumo contínuo do Kafka. No modelo thread-per-request, cada cliente SSE prende uma thread.

## Decisão
- **Spring WebFlux** com *router functions*, **Reactor Kafka** e **Spring Data MongoDB Reactive**: pipeline não bloqueante de ponta a ponta, com backpressure.
- Consumo com `concatMap` (ordem preservada por partição). O offset só é confirmado depois de gravar no Mongo. Falhas de infraestrutura recriam o receiver com backoff exponencial, e mensagens malformadas são descartadas com log (em produção, DLQ).
- `Sinks.many().multicast().directBestEffort()` distribui novos lançamentos aos clientes SSE sem deixar um cliente lento travar o consumo.
- Documento desnormalizado por conta, com índice `{accountId: 1, occurredAt: -1}` e `Decimal128` para valores monetários.

## Por que não WebFlux no account-service?
Lá o gargalo é consistência transacional (JPA, locks), não concorrência de I/O. Spring MVC com **virtual threads** (Java 21) dá throughput alto com um modelo de programação mais simples e maduro para JDBC/JPA. A escolha certa depende do problema, e usar reativo em todo lugar não é um objetivo.

## Consequências
- ✅ Milhares de conexões SSE com poucas threads.
- ⚠️ A curva de aprendizado do reativo é maior. Mitigado com testes em `StepVerifier` e handlers pequenos.
