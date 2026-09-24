# Curso: entendendo 100% do PocBank

19 aulas cobrindo todo o sistema: cada conceito, onde ele aparece no código, por que foi escolhido, as alternativas, e **como aplicar num ERP em Java + PostgreSQL**.

Todas as aulas seguem a mesma estrutura:
1. **Conceito**, com exemplos
2. **Na POC**, com links para o código
3. **Trade-offs e alternativas**
4. **No seu ERP**, o que dá para aplicar no trabalho
5. **Exercícios** práticos

## Antes de começar

Deixe o ambiente no ar. A maioria dos exercícios usa o sistema rodando:
```bash
docker compose up -d --build
docker compose exec localstack bash /opt/pocbank/deploy-lambda.sh   # Lambda no LocalStack (PowerShell)
```
Front em http://localhost:3000 · Kafka UI em http://localhost:8090 · Swagger em http://localhost:8081/swagger-ui.html

## Aulas

| # | Aula | Tema | Relevância para o ERP |
|---|---|---|---|
| 01 | [Visão geral e arquitetura](01-visao-geral-e-arquitetura.md) | Fluxo completo, bounded contexts, monolito × microsserviços | ★★★ |
| 02 | [Modelagem de domínio em Java](02-modelagem-de-dominio-em-java.md) | Modelo rico, BigDecimal, records, exceções, Clock | ★★★ |
| 03 | [SOLID e Design Patterns](03-solid-e-design-patterns.md) | Cada princípio e padrão, e quando não usar | ★★★ |
| 04 | [Arquitetura hexagonal](04-arquitetura-hexagonal.md) | Camadas, portas e adaptadores, ArchUnit | ★★★ |
| 05 | [PostgreSQL: transações e concorrência](05-postgresql-transacoes-e-concorrencia.md) | Lost update, locks, deadlock, `@Transactional`, SKIP LOCKED | ★★★ **a mais importante** |
| 06 | [Schema, migrations e performance](06-schema-migrations-e-performance.md) | Flyway, tipos, constraints, índices, expand/contract | ★★★ |
| 07 | [NoSQL e CQRS](07-nosql-e-cqrs.md) | Read models, MongoDB, views materializadas | ★★ |
| 08 | [APIs REST](08-apis-rest.md) | Status codes, idempotência, ProblemDetail, paginação | ★★★ |
| 09 | [Integrações e resiliência](09-integracoes-e-resiliencia.md) | Timeout, retry, circuit breaker, fail-closed, WireMock | ★★★ |
| 10 | [Eventos, Outbox e Kafka](10-eventos-outbox-e-kafka.md) | Dual write, outbox, conceitos do Kafka, consumidor idempotente | ★★★ (o outbox) |
| 11 | [Reativo, WebFlux e SSE](11-programacao-reativa-webflux-sse.md) | Mono/Flux, backpressure, virtual threads, SSE | ★★ |
| 12 | [Testes](12-testes.md) | JUnit 5, Mockito, WireMock, StepVerifier, Testcontainers | ★★★ |
| 13 | [Gateway, observabilidade e segurança](13-gateway-observabilidade-e-seguranca.md) | Spring Cloud Gateway, correlation-id, Actuator, OAuth2 | ★★ |
| 14 | [React e Design System](14-react-e-design-system.md) | Padrões de React, tokens, acessibilidade | ★★ |
| 15 | [Docker e Kubernetes](15-docker-e-kubernetes.md) | Multi-stage, probes, recursos, rolling update, Kustomize | ★★ |
| 16 | [AWS, Terraform e custos](16-aws-terraform-e-custos.md) | S3, Lambda, EC2, IAM, IaC, quanto custa | ★★ |
| 17 | [Git, CI/CD e code review](17-git-cicd-e-code-review.md) | Pipeline, branches, PRs, ADRs | ★★★ |
| 18 | [Ágil e concepção de produto](18-agil-e-concepcao-de-produto.md) | Scrumban, DoR/DoD, métricas, Lean Inception, Event Storming | ★★ |
| 19 | [Autocrítica](19-autocritica.md) | Tudo que está errado na POC, e como corrigir | ★★★ **para entrevista** |

## Trilhas sugeridas

**Trilha ERP** (o que mais rende no trabalho atual): 05 → 06 → 02 → 03 → 04 → 08 → 09 → 10 → 12 → 17

**Trilha entrevista** (cobrir a vaga): 01 → 02 → 03 → 05 → 08 → 09 → 10 → 11 → 12 → 15 → 16 → 19

**Trilha completa:** na ordem numérica. Cada aula leva de 1 a 3 horas com os exercícios.

## Material de apoio no repositório
- [ADRs](../adr/): as decisões de arquitetura com contexto e consequências
- [SOLID e patterns](../engineering/solid-and-patterns.md): mapa rápido de princípio → código
- [Guia de code review](../engineering/code-review.md)
- [Modo de trabalho ágil](../agile/way-of-working.md) e [concepção de produto](../product/inception.md)
