# PocBank: POC Full Stack Java + React

POC de uma plataforma bancária simplificada (contas, transferências PIX/TED com antifraude, extrato em tempo real e exportação para S3). Ela foi montada para demonstrar, em código que roda, cada competência pedida para **Desenvolvedor(a) Full Stack Sênior – Java + React (setor financeiro)**.

```
┌──────────────┐     ┌────────────────┐     ┌───────────────────┐   OpenFeign + CB   ┌──────────────┐
│ React + DS   │───▶│  API Gateway    │───▶│  account-service   │──────────────────▶│ Antifraude    │
│ (Vite, TS)   │ SSE │ Spring Cloud    │     │ Spring MVC + JPA   │   (Resilience4j)   │ (WireMock)    │
└──────────────┘◀───│ Gateway         │     │ PostgreSQL         │                    └──────────────┘
                     └───────┬────────┘     │ Transactional      │
                             │              │ Outbox ──┐         │
                             │              └──────────┼─────────┘
                             │                         ▼
                             │                ┌────────────────┐
                             │                │ Kafka          │  topic: transfer-events
                             │                └───────┬────────┘
                             ▼                        ▼
                     ┌──────────────────────────────────────┐   CSV    ┌──────┐  evento  ┌────────────────┐
                     │ statement-service                    │─────────▶│  S3  │────────▶│ Lambda (Java)  │
                     │ Spring WebFlux + Reactor Kafka       │          └──────┘          │ statement-report│
                     │ MongoDB reativo (read model / CQRS)  │               ▲             └───────┬────────┘
                     └──────────────────────────────────────┘               └──── reports/*.json ─┘
```

## Matriz: requisito da vaga → onde está na POC

| Requisito | Evidência |
|---|---|
| **Java, POO e Design Patterns** | Java 21 (records, sealed interfaces, pattern switch). Padrões: **Strategy** + resolver ([`FeePolicy`](services/account-service/src/main/java/br/com/pocbank/account/domain/fee/)), **Ports & Adapters** ([`FraudCheckPort`](services/account-service/src/main/java/br/com/pocbank/account/application/port/FraudCheckPort.java) → [`FraudCheckAdapter`](services/account-service/src/main/java/br/com/pocbank/account/infrastructure/fraud/FraudCheckAdapter.java)), **Anti-Corruption Layer**, **Transactional Outbox**, **Parameter Object** (`Transfer.Draft`), **Observer** reativo ([`StatementEventBus`](services/statement-service/src/main/java/br/com/pocbank/statement/application/StatementEventBus.java)), **Circuit Breaker**, **CQRS** (read model de extrato), **Facade** (Design System). Aggregate rico: [`Account`](services/account-service/src/main/java/br/com/pocbank/account/domain/account/Account.java) |
| **React e/ou Angular** | [`frontend/`](frontend/): React 18 + TypeScript + Vite, hooks, SSE em tempo real, formulário com validação e idempotência |
| **APIs REST e integrações** | REST com `201 + Location`, `Idempotency-Key`, erros **RFC 9457** ([`ApiExceptionHandler`](services/account-service/src/main/java/br/com/pocbank/account/web/ApiExceptionHandler.java)), OpenAPI/Swagger, integração HTTP com antifraude via **OpenFeign** |
| **Spring Boot, Spring Cloud, Spring WebFlux** | Boot 3.3; **Spring Cloud Gateway** ([`api-gateway`](services/api-gateway/)), **OpenFeign**, **Spring Cloud CircuitBreaker (Resilience4j)**; **WebFlux** com router funcional, SSE e Mongo reativo ([`statement-service`](services/statement-service/)) |
| **Microsserviços e arquitetura distribuída/orientada a eventos** | 3 serviços independentes + Lambda; comunicação assíncrona por eventos, **outbox** (sem dual write), consumidor **idempotente**, at-least-once, correlation-id ponta a ponta. [ADRs](docs/adr/) |
| **Kafka** | Produtor idempotente (`acks=all`) via [`OutboxRelay`](services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxRelay.java) com `FOR UPDATE SKIP LOCKED`; consumidor **Reactor Kafka** com backpressure, commit após processamento e tratamento de poison pill ([`TransferEventsConsumer`](services/statement-service/src/main/java/br/com/pocbank/statement/messaging/TransferEventsConsumer.java)) |
| **AWS (EC2, S3, Lambda)** | **S3**: export CSV com SDK v2 assíncrono. **Lambda** Java 21 disparada por evento S3 ([`lambda/`](lambda/statement-report-lambda/)). **EC2**: host de demonstração com IMDSv2 e SSM. Tudo em [Terraform](infra/terraform/), com IAM de menor privilégio, SnapStart e lifecycle LGPD. LocalStack para dev |
| **Kubernetes** | [`k8s/`](k8s/): Kustomize base + overlays dev/prod, probes (startup/liveness/readiness), HPA, PDB, NetworkPolicy, Pod Security `restricted`, IRSA, External Secrets, rolling update sem indisponibilidade |
| **Bancos relacionais e NoSQL** | **PostgreSQL** (JPA, Flyway, constraints, índice parcial, lock pessimista ordenado + `@Version`) e **MongoDB** (read model desnormalizado, `Decimal128`, índice composto) |
| **SOLID, Clean Code e Code Review** | [docs/engineering/solid-and-patterns.md](docs/engineering/solid-and-patterns.md), [guia de code review](docs/engineering/code-review.md), [PR template](.github/pull_request_template.md), [CODEOWNERS](.github/CODEOWNERS) |
| **Testes: JUnit, Mockito, WireMock** | **50 testes Java**: JUnit 5 (parametrizados, `@Nested`), **Mockito** (`InOrder`, captors), **WireMock** contra o Feign/CB reais ([`FraudCheckAdapterWireMockTest`](services/account-service/src/test/java/br/com/pocbank/account/infrastructure/fraud/FraudCheckAdapterWireMockTest.java)), `@WebMvcTest`, `WebTestClient`, `StepVerifier`. **8 testes** de front (Vitest + Testing Library) |
| **Git, CI/CD e automação** | [GitHub Actions](.github/workflows/ci.yml): build/teste/cobertura, lint, validação de IaC, imagens no GHCR, deploy no EKS com OIDC; Dependabot; Dockerfiles multi-stage |
| **Design System (Diana)** | O Diana é proprietário, então implementei um DS próprio baseado em **design tokens** com a mesma arquitetura ([`frontend/src/design-system`](frontend/src/design-system/)): tokens semânticos, componentes acessíveis (ARIA), dark mode e fachada única de importação que permite trocar pelo Diana sem mexer nas telas. Catálogo na aba *Design System* |
| **Metodologias ágeis (Scrum/Kanban)** | [docs/agile/way-of-working.md](docs/agile/way-of-working.md): ritos, DoR/DoD, WIP limits, métricas de fluxo |
| **Concepção colaborativa de produtos** | [docs/product/inception.md](docs/product/inception.md): visão, personas, Event Storming, story map, MVP e hipóteses |

## Como rodar

**Pré-requisito:** Docker. JDK e Node são opcionais (os scripts abaixo usam containers).

```bash
docker compose up --build -d          # sobe tudo: infra + 3 serviços + front
```

| URL | O quê |
|---|---|
| http://localhost:3000 | Frontend |
| http://localhost:8080 | API Gateway |
| http://localhost:8081/swagger-ui.html | OpenAPI do account-service |
| http://localhost:8082/swagger-ui.html | OpenAPI do statement-service |
| http://localhost:8090 | Kafka UI (veja o tópico `transfer-events`) |

### Roteiro de demonstração

Duas contas vêm pré-carregadas (Ana `1111…` e Bruno `2222…`).

1. **Transferir**: PIX de R$ 100 de Ana para Bruno. Fica `COMPLETED`, e a aba *Extrato* mostra o lançamento em tempo real (SSE, badge "novo").
2. **Antifraude**: um valor acima de R$ 10.000 volta `REJECTED` com o motivo, e nenhum saldo muda.
3. **Resiliência**: o valor **666,99** faz o antifraude simulado demorar 5s. O read-timeout do Feign (2s) corta a chamada e a API responde `503` (fail-closed). Se você insistir, o circuit breaker abre (`localhost:8081/actuator/circuitbreakers`) e as próximas chamadas falham na hora.
4. **Idempotência**: repita um `POST /api/transfers` com o mesmo `Idempotency-Key`. Volta a mesma transferência e nada é debitado duas vezes.
5. **S3 + Lambda**: implante a Lambda no LocalStack (uma vez, depois do build Maven) e use *Extrato → Exportar CSV*. O CSV cai em `exports/`, o evento S3 dispara a Lambda Java e o resumo aparece em `reports/`.

```bash
docker compose exec localstack bash /opt/pocbank/deploy-lambda.sh
docker compose exec localstack awslocal s3 ls s3://pocbank-statements --recursive
```

```bash
curl -i -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"sourceAccountId":"11111111-1111-1111-1111-111111111111","targetAccountId":"22222222-2222-2222-2222-222222222222","amount":150.00,"type":"TED"}'

# extrato em tempo real (deixe aberto e faça uma transferência em outro terminal)
curl -N localhost:8080/api/statements/22222222-2222-2222-2222-222222222222/stream
```

### Testes

```bash
# Java (sem JDK local: roda no container)
docker run --rm -v "$PWD":/workspace -v pocbank-m2:/root/.m2 -w /workspace \
  maven:3.9-eclipse-temurin-21 mvn -B verify

# Frontend
cd frontend && npm ci && npm run lint && npm run test:ci && npm run build
```

Os relatórios de cobertura (JaCoCo) ficam em `*/target/site/jacoco/index.html`.

## Estrutura

```
services/
  account-service/     Spring MVC · JPA/PostgreSQL · Flyway · OpenFeign · Resilience4j · Outbox → Kafka
  statement-service/   Spring WebFlux · Reactor Kafka · MongoDB reativo · SSE · S3 (SDK v2 async)
  api-gateway/         Spring Cloud Gateway · CORS · correlation-id · retry
lambda/
  statement-report-lambda/   AWS Lambda Java 21 (evento S3)
frontend/              React 18 · TypeScript · Vite · Vitest · design-system/
infra/
  terraform/           S3 · Lambda · EC2 · IAM
  wiremock/            contrato simulado do antifraude
  localstack/          bootstrap do S3 local
k8s/                   Kustomize (base + overlays dev/prod)
docs/                  ADRs · engenharia · ágil · produto
.github/               CI/CD · PR template · CODEOWNERS · Dependabot
```

## Curso

[`docs/aulas/`](docs/aulas/README.md): 19 aulas que explicam o sistema inteiro, com conceitos, trade-offs, exercícios e aplicação em ERP Java + PostgreSQL.

## Decisões de arquitetura

As decisões e seus trade-offs estão em [`docs/adr/`](docs/adr/):

- [0001](docs/adr/0001-microservices-and-bounded-contexts.md): microsserviços por bounded context
- [0002](docs/adr/0002-transactional-outbox.md): Transactional Outbox em vez de dual write
- [0003](docs/adr/0003-webflux-for-statements.md): WebFlux + Mongo para o extrato (CQRS)
- [0004](docs/adr/0004-fraud-check-fail-closed.md): antifraude síncrono, fail-closed e fora da transação
- [0005](docs/adr/0005-design-system-facade.md): Design System como fachada substituível
