# Aula 05: PostgreSQL, transações e concorrência

**Objetivo:** esta é a aula mais importante para quem trabalha com ERP. Ela explica como a POC impede que duas operações simultâneas corrompam dados, onde fica o limite da transação e as armadilhas do `@Transactional`.

---

## 1. O problema: lost update

Duas transferências da mesma conta chegam ao mesmo tempo. Saldo: R$ 100.

```
Tempo  Transação A (debita 80)          Transação B (debita 50)
 t1    SELECT saldo → 100
 t2                                     SELECT saldo → 100
 t3    100 ≥ 80 ✔  UPDATE saldo = 20
 t4    COMMIT
 t5                                     100 ≥ 50 ✔  UPDATE saldo = 50
 t6                                     COMMIT
```
Resultado: saldo **50**, com **130** debitados de uma conta que tinha 100. A atualização de A foi perdida. No ERP, o mesmo acontece com **estoque**: dois pedidos reservam o último item.

Por que o banco não impediu? O Postgres usa por padrão o nível de isolamento **READ COMMITTED**: cada comando enxerga o que já foi commitado. Os dois SELECTs leram 100 legitimamente. O erro está no padrão **ler → decidir no Java → gravar** sem proteção.

## 2. Quatro soluções

### a) UPDATE atômico (o banco decide)
```sql
UPDATE accounts SET balance = balance - 80
WHERE id = :id AND balance >= 80;
-- 1 linha afetada = debitou; 0 linhas = saldo insuficiente
```
O Postgres trava a linha no UPDATE. A segunda transação espera e, quando a primeira commita, **reavalia o WHERE com o valor novo**. É simples e muito rápido. A desvantagem é que a regra de negócio vai para o SQL.

### b) Lock pessimista: `SELECT ... FOR UPDATE` (o que a POC usa)
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.id = :id")
Optional<Account> findByIdForUpdate(@Param("id") UUID id);
```
A primeira transação trava a linha na leitura, e a segunda **espera** no próprio SELECT até a primeira terminar. Aí ela lê o saldo já atualizado. A regra continua no Java (`account.debit()`).

> Ative `spring.jpa.show-sql=true` e veja o SQL gerado. Dependendo da versão do Hibernate, o PostgreSQL recebe `FOR UPDATE` ou `FOR NO KEY UPDATE`; os dois bloqueiam outro `FOR UPDATE` na mesma linha.

### c) Lock otimista: `@Version`
```java
@Version
private Long version;
```
O Hibernate gera `UPDATE ... SET ..., version = 6 WHERE id = ? AND version = 5`. Se outra transação já mudou a linha, 0 linhas são afetadas e ele lança `OptimisticLockException`. Ninguém espera; o perdedor falha e precisa **tentar de novo**.

### d) Isolamento SERIALIZABLE
O banco detecta qualquer anomalia e aborta uma das transações com erro `40001`. É o mais seguro e o mais genérico, mas exige **retry** em todo lugar e custa desempenho sob contenção.

### Comparação

| Estratégia | Espera ou falha? | Bom para | Cuidado |
|---|---|---|---|
| UPDATE atômico | Espera (curto) | Contadores, saldos, estoque simples | Regra no SQL |
| Pessimista | Espera | Alta contenção, regra complexa no Java | Deadlock, transações longas travam tudo |
| Otimista | Falha e retenta | Baixa contenção (edição de cadastro) | Sob contenção alta, retry em cascata |
| SERIALIZABLE | Falha e retenta | Regras que envolvem várias linhas/tabelas | Retry obrigatório, custo |

**Por que a POC usa pessimista E `@Version`?** Transferência de uma conta popular (ex.: a conta de um lojista recebendo muitos PIX) tem **contenção alta**. Com lock otimista, a maioria das tentativas falharia e retentaria. O `@Version` fica como **segunda linha de defesa** para qualquer outro caminho que altere a conta sem o lock (ex.: um futuro `block()` via tela administrativa).

## 3. Deadlock e a ordem dos locks

```
Transação A (Ana → Bruno)             Transação B (Bruno → Ana)
 trava Ana                              trava Bruno
 tenta travar Bruno... espera B         tenta travar Ana... espera A
                  ⇒ DEADLOCK: um espera o outro para sempre
```
O Postgres detecta (após `deadlock_timeout`, padrão de 1s) e aborta uma delas com erro. Não corrompe dados, mas gera falhas aleatórias sob carga.

A solução da POC é **sempre travar na mesma ordem**, independentemente de quem é origem ou destino:
```java
private Map<UUID, Account> lockInOrder(UUID first, UUID second) {
    Map<UUID, Account> locked = new HashMap<>();
    Stream.of(first, second).sorted().forEach(id ->
            locked.put(id, accounts.findByIdForUpdate(id).orElseThrow(...)));
    return locked;
}
```
Com ordem global (por UUID), as duas transações tentam travar a mesma conta primeiro. Uma espera a outra, e o ciclo é impossível. Existe inclusive um teste garantindo a ordem (`locksAccountsInDeterministicOrderToAvoidDeadlocks`).

---

## 4. Limites da transação: o que fica dentro e o que fica fora

Uma transação segura **locks** e uma **conexão do pool** enquanto está aberta. Olhe o `TransferService`:
```java
public Transfer execute(TransferCommand command) {       // SEM @Transactional aqui
    return transfers.findByIdempotencyKey(...)
            .orElseGet(() -> process(command));
}

private Transfer process(TransferCommand command) {
    ...
    FraudDecision decision = fraudCheck.evaluate(...);    // HTTP: fora da transação
    return tx.execute(status -> decision.approved() ? settle(draft) : reject(draft, decision));
}                                                          // ↑ transação só aqui
```
### A conta que justifica isso
- Pool de 10 conexões (`maximum-pool-size: 10`).
- O antifraude demora 2 s.
- Se a chamada ficasse **dentro** da transação: 10 transferências simultâneas ocupam as 10 conexões por 2 s. A 11ª requisição, até uma simples consulta de saldo, espera. E as contas ficam **travadas** por 2 s.
- **Fora** da transação: a transação dura milissegundos.

> Regra: **nunca faça I/O remoto (HTTP, e-mail, fila, S3) com transação de banco aberta.** No ERP, o caso típico é chamar a SEFAZ dentro de um `@Transactional` que já travou o pedido.

### Transação programática × declarativa
A POC usa `TransactionOperations` (programática) no `TransferService` porque precisa de **uma parte do método** dentro da transação e outra fora. Com `@Transactional` isso exigiria dividir em outro bean, por causa da armadilha a seguir.

## 5. As armadilhas do `@Transactional`

**1. Auto-invocação ignora a anotação.**
```java
@Service
class PedidoService {
    public void faturar(Long id) {
        chamarSefaz();
        gravar(id);          // chamada interna: NÃO passa pelo proxy do Spring
    }

    @Transactional
    public void gravar(Long id) { ... }   // aqui a anotação é ignorada!
}
```
O Spring aplica `@Transactional` com um **proxy** em volta do bean. Chamadas vindas de fora passam pelo proxy; `this.gravar()` não passa. Soluções: mover para outro bean, ou usar `TransactionTemplate`/`TransactionOperations` como a POC.

**2. Método `private` ou `final` com `@Transactional`:** também é ignorado (o proxy não intercepta).

**3. Exceção checked não faz rollback por padrão.** Só `RuntimeException` e `Error`. Para checked: `@Transactional(rollbackFor = Exception.class)`.

**4. Capturar a exceção e seguir:** se você trata a exceção dentro do método transacional, o Spring não sabe que deu errado e commita.

**5. `readOnly = true`** não é só decorativo. O Hibernate pula o *dirty checking* (mais rápido) e o driver pode rotear para uma réplica de leitura.

**6. `Propagation.MANDATORY`** no `OutboxEventPublisher` falha se não houver transação aberta. É uma forma de **garantir** que o evento nunca seja gravado fora da transação do negócio.

---

## 6. Idempotência e o problema do check-then-act

```java
return transfers.findByIdempotencyKey(key)      // 1. checa
        .orElseGet(() -> process(command));     // 2. age
```
Duas requisições com a mesma chave chegam juntas: ambas checam, nenhuma acha, ambas processam. **Check-then-act nunca é seguro sozinho.** A proteção real é a constraint no banco:
```sql
idempotency_key VARCHAR(100) NOT NULL UNIQUE
```
A segunda a commitar recebe `DataIntegrityViolationException`, e o código trata:
```java
} catch (DataIntegrityViolationException e) {
    return transfers.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
}
```
> O `findByIdempotencyKey` inicial é **otimização** (evita chamar o antifraude à toa). A **garantia** é a constraint. Isso vale para qualquer unicidade: CPF, número de nota, código de produto.

**Bug real na POC:** o `AccountService.open` faz `existsByDocument` e depois `save`, mas **não trata** a violação da constraint `UNIQUE(document)`. Duas aberturas simultâneas com o mesmo CPF: uma funciona e a outra devolve **500**, quando deveria devolver 409. (Veja a [Aula 19](19-autocritica.md).)

## 7. Fila no Postgres: `FOR UPDATE SKIP LOCKED`

[`OutboxRepository.lockNextBatch`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/messaging/OutboxRepository.java):
```sql
SELECT * FROM outbox_events
WHERE published_at IS NULL
ORDER BY created_at
LIMIT :limit
FOR UPDATE SKIP LOCKED
```
Com duas réplicas do serviço rodando o relay: a réplica 1 trava as linhas 1 a 100, e a réplica 2 **pula** as travadas e pega as seguintes. Ninguém espera e ninguém processa em dobro. É o jeito padrão de fazer **fila de trabalho em Postgres**.

## 8. A constraint como última linha de defesa

```sql
balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0)
```
Mesmo que alguém escreva um `UPDATE` manual, um script de migração com bug ou um código que pule o `Account.debit`, o banco recusa saldo negativo. **O domínio valida para dar boa mensagem; o banco valida para garantir.**

---

## No seu ERP (Java + PostgreSQL)

### Reserva de estoque sem vender o que não tem
```sql
UPDATE estoque.saldo
SET reservado = reservado + :qtd
WHERE produto_id = :produto AND deposito_id = :deposito
  AND (quantidade - reservado) >= :qtd;
-- 0 linhas = sem estoque disponível
```
Para pedidos com vários itens: **ordene os itens por `produto_id`** antes de reservar, para evitar deadlock entre pedidos que compartilham produtos (mesma ideia do `lockInOrder`).

### Numeração de nota fiscal sem buraco
`SEQUENCE` do Postgres **não é transacional**: se a transação der rollback, o número foi consumido e fica um buraco. Para NF-e, buraco exige inutilização na SEFAZ. A solução é um contador por série, travado:
```sql
UPDATE fiscal.serie SET ultimo_numero = ultimo_numero + 1
WHERE empresa_id = :empresa AND serie = :serie
RETURNING ultimo_numero;
```
Isso trava a linha da série até o commit. Se houver rollback, o número volta. O preço é serializar a emissão por série, o que normalmente é aceitável.

### Fila de jobs sem biblioteca externa
Processamento de importações, envio de e-mails, integrações: uma tabela `job` + `FOR UPDATE SKIP LOCKED` + um worker `@Scheduled` resolve com vários servidores, sem RabbitMQ ou Kafka.

### Advisory locks para processos únicos
"Só um fechamento de mês por empresa por vez":
```sql
SELECT pg_try_advisory_xact_lock(hashtext('fechamento'), :empresaId);
-- true = conseguiu; false = já tem outro rodando
```
O lock é liberado sozinho no fim da transação.

### Timeouts de proteção
```sql
SET lock_timeout = '5s';           -- não espera lock para sempre
SET statement_timeout = '30s';     -- mata query descontrolada
SET idle_in_transaction_session_timeout = '60s';  -- mata transação esquecida aberta
```
Dá para configurar por usuário do banco ou no pool (Hikari `connection-init-sql`). A transação esquecida aberta (`idle in transaction`) é uma das causas mais comuns de travamento em ERP.

### Diagnóstico
```sql
-- quem está bloqueando quem
SELECT pid, pg_blocking_pids(pid) AS bloqueado_por, state, now() - xact_start AS duracao, query
FROM pg_stat_activity
WHERE cardinality(pg_blocking_pids(pid)) > 0;
```

---

## Exercícios

1. **Reproduza o lost update** com dois terminais `psql` no Postgres da POC (`docker compose exec postgres psql -U pocbank accounts`):
   - Nos dois: `BEGIN; SELECT balance FROM accounts WHERE id = '1111...';`
   - Nos dois, calcule na cabeça e rode `UPDATE accounts SET balance = <valor calculado> WHERE id = ...;` e depois `COMMIT;`
   - Repita usando `SELECT ... FOR UPDATE` no primeiro passo e observe o segundo terminal esperar.
2. **Provoque um deadlock:** terminal A trava a conta 1111 e depois a 2222; terminal B trava a 2222 e depois a 1111. Leia a mensagem do Postgres.
3. Corrija o bug do `AccountService.open` para devolver 409 em caso de corrida.
4. Procure no seu ERP um `@Transactional` que envolva uma chamada HTTP. Quanto tempo essa transação fica aberta no pior caso?
5. Rode a query de diagnóstico no banco do ERP em horário de pico.
