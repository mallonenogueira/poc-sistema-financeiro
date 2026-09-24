# Aula 06: Schema, migrations e performance no PostgreSQL

**Objetivo:** entender como o schema é versionado com Flyway, por que cada tipo e constraint foi escolhido, como os índices funcionam e como mudar o banco sem derrubar a aplicação.

---

## 1. Flyway: o banco versionado como o código

```
src/main/resources/db/migration/
  V1__create_schema.sql
  V2__seed_demo_accounts.sql
```
Na subida, o Flyway compara esses arquivos com a tabela `flyway_schema_history` e aplica os que faltam, em ordem.

Regras:
- **Nunca edite uma migration já aplicada.** O Flyway guarda o checksum e recusa subir se o arquivo mudou. Corrigiu algo? Crie `V3__...`.
- Uma migration = uma mudança lógica.
- Migrations entram no code review como qualquer código.

E no `application.yml`:
```yaml
spring.jpa.hibernate.ddl-auto: validate
```
O Hibernate **não cria nem altera** tabelas; só confere se as entidades batem com o banco e falha no boot se não baterem. `ddl-auto: update` em produção é receita de desastre: ele não remove colunas nem renomeia, e não há histórico do que mudou.

**Alternativa:** Liquibase (changelogs em XML/YAML/SQL, rollback declarativo, bom para vários bancos). Flyway é mais simples; Liquibase é mais poderoso. Os dois resolvem o problema.

## 2. Tipos escolhidos

[`V1__create_schema.sql`](../../services/account-service/src/main/resources/db/migration/V1__create_schema.sql):

| Coluna | Tipo | Por quê |
|---|---|---|
| valores | `NUMERIC(19,2)` | Decimal exato. `REAL`/`DOUBLE PRECISION` têm o mesmo problema do `double` |
| datas | `TIMESTAMPTZ` | Guarda o instante absoluto (em UTC internamente). `TIMESTAMP` sem fuso é ambíguo: "10h" de onde? |
| ids | `UUID` | Gerado na aplicação, sem ir ao banco; não expõe volume ("pedido 1523"); bom para sistemas distribuídos |
| status | `VARCHAR` + `@Enumerated(STRING)` | Legível no banco. **Nunca** `EnumType.ORDINAL`: reordenar o enum corrompe os dados |

### UUID × BIGSERIAL: o trade-off
| | `BIGSERIAL` | `UUID` v4 (aleatório) | `UUID` v7 (ordenado por tempo) |
|---|---|---|---|
| Tamanho | 8 bytes | 16 bytes | 16 bytes |
| Índice | Insere sempre no fim (ótimo) | Insere em lugar aleatório (fragmenta, mais I/O) | Quase sempre no fim |
| Gerado onde | Banco | Qualquer lugar | Qualquer lugar |
| Expõe volume | Sim | Não | Revela o instante de criação |

A POC usa `UUID.randomUUID()` (v4). Com milhões de linhas, **UUID v7** seria melhor (o Postgres 18 tem `uuidv7()`; em Java há bibliotecas). Para ERP com tabelas enormes e integração só interna, `BIGINT` continua uma escolha excelente.

## 3. Constraints: regras que o banco garante

```sql
document  VARCHAR(11) NOT NULL UNIQUE,
balance   NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
amount    NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
source_account_id UUID NOT NULL REFERENCES accounts (id),
idempotency_key VARCHAR(100) NOT NULL UNIQUE,
```
Cada uma é uma invariante que **nenhum bug de aplicação consegue violar**. Custo: praticamente zero. Se o seu banco tem pouca `CHECK` e `FOREIGN KEY`, os dados provavelmente já têm lixo.

## 4. Índices

### Índice composto: a ordem das colunas importa
```sql
CREATE INDEX idx_transfers_source ON transfers (source_account_id, created_at DESC);
```
Serve para:
- `WHERE source_account_id = ?` ✔
- `WHERE source_account_id = ? ORDER BY created_at DESC LIMIT 20` ✔✔ (já sai ordenado, sem sort)

Não serve (bem) para:
- `WHERE created_at > ?` sozinho ✘. É como uma lista telefônica ordenada por sobrenome e depois nome: você não acha rápido "todo mundo chamado João".

**Regra:** coluna de igualdade primeiro, depois a de ordenação ou intervalo.

### Índice parcial
```sql
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
```
A tabela de outbox terá milhões de eventos publicados e dezenas pendentes. O índice guarda **só os pendentes**: minúsculo e sempre na memória. A consulta do relay precisa repetir a condição (`WHERE published_at IS NULL`) para o planner usar o índice.

### O índice que falta
`UNIQUE` já cria índice (em `document` e `idempotency_key`). As FKs **não** criam índice automaticamente no Postgres. Aqui estão cobertas pelos índices compostos, mas em ERP é comum ter FK sem índice, o que deixa lento o `DELETE` da tabela pai e os joins.

### Como verificar: `EXPLAIN ANALYZE`
```sql
EXPLAIN ANALYZE
SELECT * FROM transfers
WHERE source_account_id = '11111111-1111-1111-1111-111111111111'
ORDER BY created_at DESC LIMIT 20;
```
Procure `Index Scan using idx_transfers_source` (bom) × `Seq Scan` + `Sort` (lê a tabela toda). Com poucas linhas o Postgres prefere Seq Scan, e está certo. Teste com volume realista.

## 5. JPA e desempenho

- **`spring.jpa.open-in-view: false`:** com `true` (padrão do Spring Boot!), a sessão do Hibernate fica aberta até a resposta HTTP ser escrita. Lazy loading dispara queries no meio da serialização JSON, e a conexão fica presa a requisição inteira. Desligar obriga a buscar o que precisa dentro do service.
- **N+1:** listar 100 pedidos e acessar `pedido.getCliente()` em cada um dispara 1 + 100 queries. Soluções: `JOIN FETCH`, `@EntityGraph`, ou uma projeção/DTO direto na query.
- **DTOs na saída:** a POC nunca serializa entidade. `AccountResponse.from(account)` evita lazy loading acidental e evita expor campos (o CPF sai mascarado).
- **Pool:** `hikari.maximum-pool-size: 10`. Mais conexões não é mais rápido: o Postgres tem custo por conexão, e o gargalo costuma ser CPU e disco. Uma fórmula de partida conhecida é `núcleos × 2 + discos`, e depois ajustar medindo.

---

## 6. Migrations sem downtime: expand/contract

Em produção, durante o deploy, a **versão antiga e a nova da aplicação rodam juntas** (rolling update). A migration precisa funcionar com as duas.

**Exemplo:** renomear `holder_name` para `full_name`.

Errado: `ALTER TABLE accounts RENAME COLUMN holder_name TO full_name;` Os pods antigos quebram na hora.

Certo, em etapas:
```
Deploy 1 (expand):   ADD COLUMN full_name; app nova escreve nas DUAS colunas e lê de full_name
                     com fallback; backfill: UPDATE ... SET full_name = holder_name (em lotes)
Deploy 2:            app lê e escreve só full_name
Deploy 3 (contract): DROP COLUMN holder_name
```

Outros cuidados:
- **`CREATE INDEX CONCURRENTLY`** não bloqueia escrita (mas não roda dentro de transação; no Flyway, marque a migration como não transacional).
- **`ADD COLUMN ... DEFAULT x`** é instantâneo desde o Postgres 11. `ADD COLUMN ... NOT NULL` sem default em tabela com dados falha.
- **Backfill em lotes** (ex.: 10 mil linhas por vez). Um `UPDATE` de 50 milhões de linhas trava a tabela e incha o WAL.
- **`SET lock_timeout`** no início da migration. Se ela não conseguir o lock em poucos segundos, falha em vez de enfileirar todo o tráfego atrás dela.

---

## No seu ERP (Java + PostgreSQL)

### Particionamento para tabelas gigantes
Movimentação de estoque, lançamentos contábeis, log de auditoria: crescem sem parar e quase sempre são consultados por período.
```sql
CREATE TABLE estoque.movimento (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    produto_id BIGINT NOT NULL,
    data_movimento DATE NOT NULL,
    quantidade NUMERIC(15,4) NOT NULL,
    PRIMARY KEY (id, data_movimento)
) PARTITION BY RANGE (data_movimento);

CREATE TABLE estoque.movimento_2026_09 PARTITION OF estoque.movimento
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
```
Consultas por período só leem as partições necessárias, e arquivar um ano antigo vira `DETACH PARTITION` em vez de um `DELETE` gigante.

### BRIN para colunas de data em tabelas append-only
```sql
CREATE INDEX idx_movimento_data ON estoque.movimento USING brin (data_movimento);
```
Minúsculo (KBs em vez de GBs) e eficiente quando os dados chegam em ordem cronológica.

### JSONB para campos customizáveis
ERP sempre tem "campo personalizado do cliente". Em vez de 50 colunas `campo_extra_1..50`:
```sql
ALTER TABLE vendas.pedido ADD COLUMN atributos JSONB NOT NULL DEFAULT '{}';
CREATE INDEX idx_pedido_atributos ON vendas.pedido USING gin (atributos);
SELECT * FROM vendas.pedido WHERE atributos @> '{"canal": "ecommerce"}';
```

### Auditoria
Tabela de histórico alimentada por trigger, ou Hibernate Envers. Para dados fiscais, o histórico de quem alterou o quê costuma ser exigência de auditoria.

### Checklist
- [ ] `ddl-auto` é `validate` ou `none` em produção?
- [ ] Toda FK usada em join ou delete tem índice?
- [ ] `open-in-view` está desligado?
- [ ] Alguma coluna de dinheiro em `double precision`? Alguma data em `timestamp` sem fuso?
- [ ] Algum `@Enumerated` sem `EnumType.STRING`?
- [ ] As migrations do último ano teriam funcionado com a versão anterior da aplicação no ar?

---

## Exercícios

1. Rode o `EXPLAIN ANALYZE` acima no Postgres da POC. Depois insira 100 mil transfers (`INSERT ... SELECT FROM generate_series`) e rode de novo. O plano mudou?
2. Escreva a migration `V3` que adiciona `email` em `accounts` com o padrão expand/contract (sem quebrar a versão atual).
3. Troque o `EnumType.STRING` de `status` por `ORDINAL` mentalmente: o que aconteceria com os dados se alguém inserisse `PENDING` no início do enum?
4. No ERP, ache a maior tabela (`SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;`). Ela é candidata a particionamento?
