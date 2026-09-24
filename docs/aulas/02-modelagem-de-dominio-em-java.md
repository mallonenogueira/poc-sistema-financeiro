# Aula 02: Modelagem de domínio em Java

**Objetivo:** entender como as regras de negócio foram colocadas nos objetos (e não espalhadas em services), como tratar dinheiro corretamente e quais recursos do Java moderno foram usados.

---

## 1. Modelo rico × modelo anêmico

### Anêmico (comum em muitos sistemas)
```java
// Entidade só com getters/setters; a regra fica no service
public class Conta {
    private BigDecimal saldo;
    public void setSaldo(BigDecimal saldo) { this.saldo = saldo; }
    public BigDecimal getSaldo() { return saldo; }
}

// Em algum service...
if (conta.getSaldo().compareTo(valor) >= 0) {
    conta.setSaldo(conta.getSaldo().subtract(valor));
}
// ...e em outro service alguém esquece o if e deixa o saldo negativo.
```

### Rico (o que a POC faz)
[`Account`](../../services/account-service/src/main/java/br/com/pocbank/account/domain/account/Account.java):
```java
public void debit(BigDecimal amount) {
    ensureActive();                 // conta bloqueada não movimenta
    requirePositive(amount);        // valor tem que ser > 0
    if (balance.compareTo(amount) < 0) {
        throw new InsufficientFundsException(id, balance, amount);
    }
    balance = Amounts.normalize(balance.subtract(amount));
}
```
Não existe `setBalance`. **A única forma de mudar o saldo é pelas regras.** Não importa quantos services, jobs ou endpoints o sistema tenha: nenhum consegue deixar o saldo negativo.

> **Invariante** é uma regra que precisa ser verdadeira sempre ("saldo ≥ 0", "conta bloqueada não movimenta"). No modelo rico, quem guarda a invariante é o próprio objeto.

## 2. Entidade, Value Object e Aggregate

| Conceito | Definição | Na POC |
|---|---|---|
| **Entidade** | Tem identidade; duas contas com o mesmo saldo são contas diferentes | `Account`, `Transfer` |
| **Value Object** | Definido só pelos valores, imutável; dois "R$ 10,00" são iguais | `TransferCommand`, os eventos (records) |
| **Aggregate** | Grupo de objetos alterados juntos, com uma raiz que protege as invariantes | `Account` é raiz do próprio aggregate |

Regra dos aggregates: **uma transação altera, idealmente, um aggregate**. A transferência altera dois (origem e destino). Por isso ela precisa de cuidados extras de concorrência ([Aula 05](05-postgresql-transacoes-e-concorrencia.md)).

## 3. Factory methods

```java
Account.open(holderName, document, initialDeposit, now)   // em vez de new Account(...) + setters
Transfer.completed(draft)
Transfer.rejected(draft, reason)
TransferCompletedEvent.of(transfer)
```
Vantagens:
- **O nome revela a intenção.** `Transfer.rejected(...)` diz mais que `new Transfer(..., REJECTED, ...)`.
- **Validação num ponto só.** Não existe `Account` criada sem passar pelas regras de abertura.
- **O construtor fica `private`/`protected`.** O `protected Account()` sem argumentos existe só porque o JPA exige.

## 4. Parameter Object

`Transfer` tem oito campos. Um construtor com oito parâmetros posicionais é um convite a trocar `amount` por `fee` sem o compilador perceber. A POC agrupa tudo em `Transfer.Draft`, um record com nomes. Outra alternativa seria um Builder.

---

## 5. Dinheiro: nunca `double`

```java
System.out.println(0.1 + 0.2);            // 0.30000000000000004
System.out.println(new BigDecimal("0.1").add(new BigDecimal("0.2")));  // 0.3
```
`double` é binário e não representa 0,1 exatamente. Em sistema financeiro ou ERP, **dinheiro é sempre `BigDecimal`** (e `NUMERIC` no banco).

### As armadilhas do BigDecimal

**1. Construir a partir de `double` herda o erro.**
```java
new BigDecimal(0.1)      // 0.1000000000000000055511151231257827...
new BigDecimal("0.1")    // 0.1   ✔
BigDecimal.valueOf(0.1)  // 0.1   ✔ (usa a representação em String)
```

**2. `equals` compara a escala; `compareTo` compara o valor.**
```java
new BigDecimal("1.0").equals(new BigDecimal("1.00"))     // false!
new BigDecimal("1.0").compareTo(new BigDecimal("1.00"))  // 0 (iguais)
```
Por isso os testes usam `isEqualByComparingTo("5.10")`, e o domínio usa `compareTo`.

**3. Divisão sem arredondamento pode lançar exceção.**
```java
BigDecimal.ONE.divide(new BigDecimal("3"))                        // ArithmeticException
BigDecimal.ONE.divide(new BigDecimal("3"), 2, RoundingMode.HALF_EVEN) // 0.33
```

### Arredondamento centralizado
[`Amounts`](../../services/account-service/src/main/java/br/com/pocbank/account/domain/Amounts.java) define a regra num lugar só: 2 casas, `HALF_EVEN`.

| Modo | 2,345 → | 2,355 → | Uso |
|---|---|---|---|
| `HALF_UP` | 2,35 | 2,36 | O "arredondamento da escola" |
| `HALF_EVEN` | 2,34 | 2,36 | "Arredondamento bancário": empata para o par, e o erro acumulado em muitas operações tende a zero |

Qual usar **é decisão de negócio ou legal**, não técnica. Cálculo de imposto, por exemplo, costuma seguir a regra definida na legislação ou no manual do documento fiscal. O importante é **decidir uma vez e centralizar**.

### O que ficou devendo: um Value Object `Money`
A POC usa `BigDecimal` solto + `Amounts`. É um meio-termo. O ideal seria:
```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(currency);
        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    }
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }
    public boolean isGreaterThan(Money other) { ... }
    private void requireSameCurrency(Money other) { ... }
}
```
Com isso o compilador impede somar "valor" com "quantidade", e o arredondamento fica impossível de esquecer.

---

## 6. Java moderno usado na POC

| Recurso | Versão | Onde | Para quê |
|---|---|---|---|
| **Records** | 16 | DTOs, eventos, `TransferCommand`, `Transfer.Draft` | Classes de dados imutáveis sem boilerplate |
| **Compact constructor** | 16 | `TransferCommand` | Validar e normalizar na criação do record |
| **Text blocks** | 15 | SQL no `OutboxRepository`, JSON nos testes | Strings multilinha legíveis |
| **`String.formatted`** | 15 | Mensagens de exceção | Formatação fluente |
| **Sealed interfaces** | 17 | `DomainEvent permits ...` | Hierarquia fechada: o compilador conhece todos os subtipos |
| **`Stream.toList()`** | 16 | Controllers | Lista imutável sem `collect(...)` |
| **Virtual threads** | 21 | `spring.threads.virtual.enabled` | Muitas requisições concorrentes com pouco custo |

Validação no record ([`TransferCommand`](../../services/account-service/src/main/java/br/com/pocbank/account/application/TransferCommand.java)):
```java
public record TransferCommand(UUID sourceAccountId, UUID targetAccountId, BigDecimal amount,
                              TransferType type, String idempotencyKey) {
    public TransferCommand {                       // compact constructor
        if (sourceAccountId.equals(targetAccountId)) {
            throw new InvalidTransferException("Conta de origem e destino devem ser diferentes");
        }
        amount = Amounts.normalize(amount);        // normaliza antes de atribuir
    }
}
```
**Um `TransferCommand` inválido não existe.** Se você tem um, ele é válido.

### Se o seu ERP está em Java 8 ou 11
- Record → classe `final` com campos `final`, construtor validando, `equals/hashCode` (ou Lombok `@Value`).
- Sealed → classe abstrata com construtor package-private (aproximação).
- Text block → concatenação, ou SQL em arquivo `.sql`.

Os **conceitos** (imutabilidade, validar na criação, hierarquias fechadas) valem em qualquer versão.

---

## 7. Exceções de domínio

```
RuntimeException
 └── DomainException (abstrata)
      ├── AccountNotFoundException
      ├── AccountBlockedException
      ├── InsufficientFundsException
      ├── DuplicateAccountException
      ├── InvalidTransferException
      └── TransferNotFoundException
```
- **Unchecked:** o chamador não é obrigado a tratar, e a exceção atravessa camadas até o handler web.
- **O domínio não conhece HTTP.** Quem decide que `InsufficientFunds` vira 422 é o [`ApiExceptionHandler`](../../services/account-service/src/main/java/br/com/pocbank/account/web/ApiExceptionHandler.java), na camada web.
- **Mensagens com contexto** (qual conta, saldo, valor pedido), o que ajuda muito no suporte.

Alternativa: retornar `Result<Ok, Erro>` em vez de lançar exceção (estilo funcional). É mais explícito, porém mais verboso em Java. Exceções são o idioma padrão do ecossistema Spring.

## 8. Tempo como dependência: `Clock`

```java
public TransferService(..., Clock clock) { ... }
Transfer.Draft(..., clock.instant())      // em vez de Instant.now()
```
No teste: `Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)`. Todo código que depende de data fica **determinístico e testável**.

## 9. Trade-off: anotações JPA no domínio

`Account` tem `@Entity`, `@Column`. Um purista diria que o domínio não pode conhecer o JPA, e teria uma classe de domínio pura mais uma `AccountJpaEntity` com mapeamento entre as duas.

| Abordagem | Prós | Contras |
|---|---|---|
| **Domínio anotado (POC)** | Menos código, sem mapeamento | Domínio acoplado ao JPA (construtor vazio, proxies) |
| **Domínio puro + entidade JPA** | Domínio 100% independente | Duplicação e mapeamento a manter |

Escolhi o pragmático. O que importa é que **as regras continuam no objeto**, com ou sem anotação.

---

## 10. No seu ERP (Java + PostgreSQL)

**Pedido como aggregate, com máquina de estados:**
```java
public class Pedido {
    private StatusPedido status = StatusPedido.RASCUNHO;
    private final List<ItemPedido> itens = new ArrayList<>();

    public void adicionarItem(Produto produto, BigDecimal quantidade, Money precoUnitario) {
        exigirStatus(StatusPedido.RASCUNHO);   // pedido faturado não muda
        itens.add(new ItemPedido(produto, quantidade, precoUnitario));
    }

    public void aprovar() {
        exigirStatus(StatusPedido.RASCUNHO);
        if (itens.isEmpty()) throw new PedidoSemItensException(id);
        status = StatusPedido.APROVADO;
    }

    public Money total() {                     // calculado, nunca "setado"
        return itens.stream().map(ItemPedido::subtotal).reduce(Money.ZERO, Money::plus);
    }

    public List<ItemPedido> itens() { return List.copyOf(itens); }   // lista protegida
}
```

Checklist para aplicar:
- [ ] Procure `setStatus(`, `setSaldo(`, `setTotal(` no código. Cada um é uma invariante desprotegida.
- [ ] Troque por métodos com nome de negócio: `aprovar()`, `cancelar(motivo)`, `baixar(quantidade)`.
- [ ] Todo valor monetário em `BigDecimal` construído por `String`, com arredondamento centralizado.
- [ ] Grep por `new BigDecimal(` com argumento `double`, e por `.equals(` entre BigDecimals.
- [ ] Injete `Clock` em quem calcula vencimento, juros, fechamento de período.
- [ ] Crie exceções de domínio com contexto em vez de `RuntimeException("erro")`.

---

## Exercícios

1. Crie o record `Money` e substitua `BigDecimal` em `Account`. Quantos testes quebram? O que o compilador passou a pegar?
2. Adicione `Account.unblock()` com a regra "só desbloqueia se estiver bloqueada". Escreva o teste primeiro.
3. Calcule na mão a tarifa TED de R$ 7.777,77 (`5 + 0,1%`, teto de 25) com `HALF_UP` e com `HALF_EVEN`. Dá diferença? Ache um valor em que dê.
4. No seu ERP, escolha uma entidade anêmica e liste as invariantes dela que hoje estão espalhadas em services.
