# Aula 01: Visão geral e arquitetura

**Objetivo:** entender o que o sistema faz, como as peças conversam e por que ele foi dividido assim. As outras aulas aprofundam cada peça; esta dá o mapa.

---

## 1. O problema de negócio

Um banco simplificado com quatro capacidades:

1. **Abrir conta** (titular, CPF, depósito inicial).
2. **Transferir** entre contas via PIX (sem tarifa) ou TED (com tarifa), com **análise antifraude** antes de mover dinheiro.
3. **Ver o extrato**, inclusive recebendo lançamentos novos **em tempo real**.
4. **Exportar o extrato** em CSV e gerar um **resumo automático**.

## 2. As peças

| Peça | Tecnologia | Responsabilidade |
|---|---|---|
| `frontend` | React + TypeScript | Telas e Design System |
| `api-gateway` | Spring Cloud Gateway | Porta de entrada única: roteamento, CORS, correlation-id |
| `account-service` | Spring MVC + JPA + PostgreSQL | Contas e transferências (escrita, consistência forte) |
| `statement-service` | Spring WebFlux + MongoDB | Extrato (leitura, tempo real, exportação) |
| Kafka | Apache Kafka | Transporta eventos entre os serviços |
| `fraud-api` | WireMock | Simula o antifraude externo |
| S3 + Lambda | AWS (LocalStack em dev) | Guarda exports e gera resumos |

## 3. Uma transferência, passo a passo

Siga isto com o código aberto. É o melhor exercício para entender o sistema inteiro.

```
 1. Front       POST /api/transfers  (header Idempotency-Key: abc)
 2. Gateway     gera X-Correlation-Id, roteia para o account-service
 3. Account     TransferController → TransferCommand (valida entrada)
 4. Account     TransferService.execute: essa Idempotency-Key já existe? → devolve a original
 5. Account     calcula a tarifa (FeePolicy PIX/TED)
 6. Account     chama o antifraude (HTTP, FORA da transação de banco)
 7. Account     abre a transação:
                  - trava as duas contas (SELECT ... FOR UPDATE, em ordem)
                  - debita a origem e credita o destino
                  - grava a Transfer
                  - grava o evento na tabela outbox_events
                commit
 8. Account     responde 201 Created
 ── assíncrono ──
 9. OutboxRelay lê outbox_events pendentes e publica no Kafka (tópico transfer-events)
10. Statement   consome o evento e grava 2 lançamentos no Mongo (débito e crédito)
11. Statement   empurra o lançamento para quem está com o extrato aberto (SSE)
12. Front       mostra o lançamento com o badge "novo"
```

Arquivos para acompanhar, na ordem:
[`TransferController`](../../services/account-service/src/main/java/br/com/pocbank/account/web/TransferController.java) →
[`TransferService`](../../services/account-service/src/main/java/br/com/pocbank/account/application/TransferService.java) →
[`OutboxEventPublisher`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxEventPublisher.java) →
[`OutboxRelay`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxRelay.java) →
[`TransferEventsConsumer`](../../services/statement-service/src/main/java/br/com/pocbank/statement/messaging/TransferEventsConsumer.java) →
[`StatementProjection`](../../services/statement-service/src/main/java/br/com/pocbank/statement/application/StatementProjection.java) →
[`StatementHandler.stream`](../../services/statement-service/src/main/java/br/com/pocbank/statement/web/StatementHandler.java).

Os passos 1 a 8 são **síncronos**: o cliente espera. Do 9 em diante é tudo **assíncrono**: o cliente já recebeu a resposta. Essa divisão é a decisão de arquitetura mais importante do sistema.

---

## 4. Conceito: bounded context (DDD estratégico)

Um **bounded context** é uma fronteira dentro da qual um modelo tem significado único. "Lançamento" no extrato não é a mesma coisa que "Transfer" no contexto de contas:

- **Transfer** tem status, idempotência, tarifa, motivo de rejeição. É o registro de uma operação.
- **StatementEntry** é uma linha no extrato de uma conta. Uma Transfer gera duas linhas (débito na origem, crédito no destino), e uma Transfer rejeitada não gera nenhuma.

Cada contexto tem modelo, banco e ciclo de vida próprios. A regra de ouro: **um contexto nunca lê o banco do outro**. Eles conversam por API ou por eventos.

## 5. Conceito: consistência forte × eventual

| | Contas (forte) | Extrato (eventual) |
|---|---|---|
| Garantia | Depois do commit, qualquer leitura vê o saldo novo | O lançamento aparece "logo", em milissegundos a segundos |
| Como | Uma transação ACID no Postgres | Evento → Kafka → projeção no Mongo |
| Custo | Locks, menos escalabilidade de escrita | Janela em que o extrato está desatualizado |

Pergunta de negócio que decide isso: **"o que acontece se isso ficar desatualizado por 1 segundo?"** Saldo desatualizado permite gastar duas vezes. Extrato desatualizado por 1 segundo não causa dano (e o SSE esconde a janela do usuário).

---

## 6. Por que microsserviços? (e o trade-off honesto)

### O que ganhamos
- Escalar leitura (extrato) sem escalar escrita (contas).
- Tecnologias diferentes onde fazem sentido (JPA transacional × WebFlux reativo).
- Falha isolada: se o extrato cair, transferências continuam.
- Times diferentes podem implantar de forma independente.

### O que pagamos
- **Complexidade operacional:** 3 serviços, Kafka, 2 bancos, rastreamento distribuído.
- **Consistência eventual** e tudo o que ela traz: idempotência, ordenação, reprocessamento.
- **Depuração difícil:** um bug pode atravessar 4 processos.
- **Latência de rede** entre peças que antes eram chamadas de método.

### A verdade
Para **um time pequeno** construindo este produto, eu começaria com um **monolito modular**: um deploy só, módulos com fronteiras rígidas dentro dele. A POC usa microsserviços porque a vaga pede e porque o objetivo é demonstrar as técnicas.

| Estilo | Quando usar |
|---|---|
| **Monolito tradicional** | Produto pequeno, um time, domínio simples |
| **Monolito modular** | Domínio grande, poucos times, quer fronteiras sem custo distribuído |
| **Microsserviços** | Vários times autônomos, necessidades de escala muito diferentes, maturidade operacional |

A regra prática: **você extrai um microsserviço quando a dor de mantê-lo junto é maior que a dor de operá-lo separado.** Não antes.

---

## 7. No seu ERP (Java + PostgreSQL)

Um ERP é o caso clássico de **monolito modular**. Os módulos já são bounded contexts naturais:

```
erp/
  vendas/       pedido, orçamento, tabela de preço
  estoque/      saldo, reserva, movimentação
  fiscal/       nota fiscal, impostos, SEFAZ
  financeiro/   contas a pagar/receber, conciliação
  compras/
```

O que você pode aplicar amanhã, sem mudar a arquitetura:

1. **Fronteiras explícitas entre módulos.** Cada módulo expõe uma API interna (um pacote `api` ou uma interface `EstoqueFacade`), e os outros só chamam por ela, nunca direto no repositório ou na tabela alheia.
2. **Um schema do Postgres por módulo** (`vendas.pedido`, `estoque.saldo`). Isso deixa visível quem é dono de cada tabela.
3. **Proibir joins entre módulos** no código novo. O relatório que precisa cruzar dados é um caso à parte (veja a [Aula 07](07-nosql-e-cqrs.md), sobre read models).
4. **Validar as fronteiras em teste**, com ArchUnit ou Spring Modulith (exemplo na [Aula 04](04-arquitetura-hexagonal.md)).
5. **Perguntar "forte ou eventual?"** para cada integração entre módulos. Faturar um pedido e baixar o estoque precisa ser forte (mesma transação). Atualizar o dashboard de vendas pode ser eventual.

Se um dia um módulo precisar sair (ex.: o fiscal virar um serviço porque escala diferente), as fronteiras já estão prontas.

---

## Exercícios

1. Suba o ambiente (`docker compose up -d`), faça uma TED pelo front e encontre o `X-Correlation-Id` dela nos logs: `docker compose logs account-service | grep <id>`.
2. Abra o Kafka UI (http://localhost:8090), ache a mensagem da sua transferência no tópico `transfer-events` e identifique a chave, os headers e o envelope.
3. Pare o statement-service (`docker compose stop statement-service`), faça duas transferências e suba de novo. O que acontece com o extrato? Por quê?
4. Desenhe o mapa de bounded contexts do seu ERP. Para cada seta entre módulos, marque se a consistência precisa ser forte ou pode ser eventual.
