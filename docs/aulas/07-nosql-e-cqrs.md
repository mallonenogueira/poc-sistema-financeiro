# Aula 07: NoSQL e CQRS

**Objetivo:** entender por que o extrato mora em outro banco, o que é CQRS, quando o MongoDB faz sentido e (principalmente) quando **não** faz.

---

## 1. CQRS: separar escrita de leitura

**Command Query Responsibility Segregation:** o modelo que **grava** não precisa ser o modelo que **lê**.

```
          ESCRITA (commands)                        LEITURA (queries)
  POST /transfers → account-service           GET /statements/{conta} → statement-service
  Postgres normalizado: accounts, transfers   Mongo desnormalizado: statement_entries
  foco: consistência, invariantes, locks      foco: consulta rápida no formato da tela
                    │                                        ▲
                    └──── evento TransferCompleted ──────────┘
                           (projeção)
```

### Por que separar
O extrato é "todos os lançamentos da conta X, do mais recente para o mais antigo". No modelo de escrita, isso vira:
```sql
SELECT ... FROM transfers WHERE source_account_id = :x
UNION ALL
SELECT ... FROM transfers WHERE target_account_id = :x
ORDER BY created_at DESC
```
Funciona, mas tem lógica de exibição (débito/crédito, sinal, contraparte) dentro da consulta, e disputa recursos com as transferências. No read model, **cada documento já é uma linha do extrato**:
```json
{
  "_id": "a188...:CREDIT",
  "accountId": "2222...",
  "direction": "CREDIT",
  "amount": NumberDecimal("150.00"),
  "counterpartyAccountId": "1111...",
  "type": "TED",
  "occurredAt": ISODate("2026-09-23T20:29:08Z")
}
```
e a consulta é `find({accountId}).sort({occurredAt: -1})`, apoiada no índice `{accountId: 1, occurredAt: -1}`.

## 2. A projeção

[`StatementProjection`](../../services/statement-service/src/main/java/br/com/pocbank/statement/application/StatementProjection.java) transforma **1 evento em 2 documentos**:
- débito na origem: `amount + fee`, contraparte = destino;
- crédito no destino: `amount`, contraparte = origem.

### Idempotência pela chave
```java
public static String idFor(String transferId, EntryDirection direction) {
    return transferId + ":" + direction;     // "a188...:DEBIT"
}
```
O Kafka entrega **pelo menos uma vez**: o mesmo evento pode chegar duas vezes. Como o `_id` é **determinístico**, o segundo `save` **sobrescreve** o mesmo documento em vez de duplicar. Não precisa de tabela de "eventos já processados".

> Se o `_id` fosse `UUID.randomUUID()`, cada reentrega criaria uma linha nova, e o cliente veria a transferência duas vezes no extrato.

## 3. Por que MongoDB aqui

| Motivo | Detalhe |
|---|---|
| Documento = linha do extrato | Sem joins nem mapeamento |
| Driver reativo maduro | Casa com o WebFlux ([Aula 11](11-programacao-reativa-webflux-sse.md)) |
| Escala horizontal (sharding por `accountId`) | Extrato é o dado que mais cresce |
| Schema flexível | Tipos de lançamento novos (tarifa, estorno) com campos diferentes |

Detalhe importante: **`Decimal128`** para dinheiro.
```java
@Field(targetType = FieldType.DECIMAL128) BigDecimal amount
```
Sem isso, o Spring Data grava `BigDecimal` como **String** no Mongo por padrão, e você perde ordenação e soma no banco. `double` teria o problema de precisão.

## 4. O custo honesto

- **Dois bancos para operar**, fazer backup e monitorar.
- **Consistência eventual:** há uma janela em que o saldo mudou e o extrato ainda não.
- **Reconstrução:** se a projeção tiver bug, você precisa reprocessar. De onde? Do Kafka, **se a retenção do tópico ainda tiver os eventos** (o padrão é 7 dias). Senão, de um *backfill* lendo o Postgres. Um sistema real precisa de uma estratégia para isso desde o dia 1.
- **Ordem:** eventos da mesma transferência chegam em ordem (mesma chave e partição), mas de transferências diferentes, não necessariamente. O extrato ordena por `occurredAt`, então não importa aqui. Em projeções que acumulam valor (ex.: saldo), importaria.

### A verdade sobre esta POC
**Para esse volume, o Postgres resolveria sozinho.** Uma tabela `statement_entries` no próprio Postgres, alimentada na mesma transação (ou por uma view), daria extrato consistente sem banco extra. O Mongo está aqui para demonstrar NoSQL e CQRS com bancos separados, que a vaga pede.

## 5. Quando usar cada coisa

| Necessidade | Opção mais simples primeiro |
|---|---|
| Relatório que cruza muitas tabelas e fica lento | **View materializada** no Postgres |
| Consultas de leitura atrapalhando a escrita | **Réplica de leitura** do Postgres |
| Atributos variáveis por registro | **JSONB** no Postgres |
| Busca textual, filtros facetados | **Elasticsearch/OpenSearch** |
| Dado lido demais e que muda pouco | **Cache** (Redis, Caffeine) |
| Volume massivo de documentos por chave, escala horizontal | **MongoDB, DynamoDB, Cassandra** |

> "Postgres primeiro" é uma boa regra. Traga outro banco quando existir um problema concreto que o Postgres não resolve bem.

### Famílias de NoSQL (para conversar em entrevista)
| Tipo | Exemplos | Bom para |
|---|---|---|
| Documento | MongoDB, DocumentDB | Agregados lidos inteiros, schema flexível |
| Chave-valor | Redis, DynamoDB | Cache, sessão, contadores, acesso por chave |
| Colunar largo | Cassandra | Escrita massiva, séries temporais por chave |
| Grafo | Neo4j | Relacionamentos (detecção de fraude em rede, recomendação) |
| Busca | Elasticsearch | Texto livre, agregações |

---

## No seu ERP (Java + PostgreSQL)

Você provavelmente **não precisa** de Mongo. Mas o **CQRS como ideia** resolve dores reais de ERP:

### Relatórios gerenciais com view materializada
O "dashboard de vendas por vendedor e mês" que faz join de pedido, item, cliente, vendedor e nota e demora 40 s:
```sql
CREATE MATERIALIZED VIEW relatorio.vendas_mensais AS
SELECT v.id AS vendedor_id, date_trunc('month', n.emissao) AS mes,
       sum(i.valor_total) AS total, count(DISTINCT n.id) AS notas
FROM fiscal.nota n
JOIN fiscal.nota_item i ON i.nota_id = n.id
JOIN vendas.vendedor v ON v.id = n.vendedor_id
WHERE n.status = 'AUTORIZADA'
GROUP BY 1, 2;

CREATE UNIQUE INDEX ON relatorio.vendas_mensais (vendedor_id, mes);  -- exigido pelo CONCURRENTLY

-- num job de hora em hora; CONCURRENTLY não bloqueia quem está lendo
REFRESH MATERIALIZED VIEW CONCURRENTLY relatorio.vendas_mensais;
```
Isso é CQRS: um modelo de leitura, desnormalizado, atualizado de forma eventual.

### Tabela de saldo consolidado atualizada por evento
Em vez de calcular o saldo de estoque somando todas as movimentações a cada consulta, mantenha `estoque.saldo` atualizado junto com cada movimentação (mesma transação), ou por um job que consome o outbox. A consulta de saldo vira leitura de uma linha.

### Projeções idempotentes
Se você tem jobs que "processam" registros (ex.: gerar comissão a partir de notas), use a chave do registro de origem como chave do resultado (`comissao.nota_id UNIQUE`) e `INSERT ... ON CONFLICT DO UPDATE`. Rodar o job duas vezes não duplica comissão. É a mesma ideia do `transferId:DIREÇÃO`.

---

## Exercícios

1. Consulte o Mongo da POC: `docker compose exec mongo mongosh statements --eval 'db.statement_entries.find().sort({occurredAt:-1}).limit(5)'`. Confira o `_id` e o tipo do `amount`.
2. Rode `db.statement_entries.find({accountId:"2222..."}).sort({occurredAt:-1}).explain("executionStats")` e ache o nome do índice usado.
3. Apague a coleção (`db.statement_entries.drop()`), mude o `group-id` do consumidor e reinicie o statement-service. O extrato se reconstrói? Até quando isso funcionaria?
4. Escreva a versão Postgres do extrato: uma view `statement_entries` sobre `transfers` com `UNION ALL`. Compare a complexidade com o Mongo.
5. No ERP, escolha o relatório mais lento e esboce a view materializada dele.
