# Aula 03: SOLID e Design Patterns

**Objetivo:** reconhecer cada princípio e padrão no código da POC, entender o problema que ele resolve e saber quando **não** usar.

> Padrões são respostas a problemas recorrentes. Se o problema não existe, o padrão vira complexidade gratuita.

---

## Parte 1: SOLID

### S: Single Responsibility (uma razão para mudar)

Na POC, publicar um evento foi dividido em três classes:

| Classe | Responsabilidade | Muda quando... |
|---|---|---|
| `TransferService` | Orquestrar o caso de uso | A regra de transferência muda |
| `OutboxEventPublisher` | Serializar e gravar no outbox | O formato do envelope muda |
| `OutboxRelay` | Entregar ao Kafka | A estratégia de entrega muda (ex.: CDC) |

**Contraexemplo:** um `TransferService` que monta JSON, grava no banco e chama `kafkaTemplate.send`. Trocar o Kafka por SQS obrigaria a mexer na regra de transferência.

> Cuidado com a leitura errada de SRP ("uma classe faz uma coisa só"), que produz centenas de classes minúsculas. O critério é **motivo de mudança**, não quantidade de métodos.

### O: Open/Closed (aberto para extensão, fechado para modificação)

[`FeePolicy`](../../services/account-service/src/main/java/br/com/pocbank/account/domain/fee/FeePolicy.java):
```java
public interface FeePolicy {
    TransferType type();
    BigDecimal calculate(BigDecimal amount);
}
```
Para uma modalidade nova (TEF, boleto), você **cria** `TefFeePolicy` e registra como bean. `TransferService` **não muda**.

**Contraexemplo:**
```java
BigDecimal fee = switch (type) {
    case PIX -> BigDecimal.ZERO;
    case TED -> ...;
    // cada modalidade nova = editar este switch, que vive dentro do caso de uso
};
```
Um `switch` num lugar só, com dois casos estáveis, **não é pecado**. O OCP vale a pena quando a variação é frequente ou quando as regras são grandes.

### L: Liskov Substitution

Qualquer implementação de `DomainEvent` pode ir para `DomainEventPublisher.publish()` sem o publisher testar o tipo. Se uma subclasse lançasse exceção em `aggregateId()`, quebraria o contrato, e isso seria uma violação de Liskov.

Violação clássica: `Quadrado extends Retangulo` em que `setLargura` muda também a altura. Quem usa `Retangulo` espera que `setLargura` não mexa na altura.

### I: Interface Segregation

`FraudCheckPort` tem **um** método. `DomainEventPublisher` tem **um** método. Cada caso de uso depende só do que usa.

**Contraexemplo:** um `IntegracaoService` com `consultarFraude`, `enviarEmail`, `emitirBoleto`, `consultarCep`. Todo mundo que precisa de um método depende de tudo, e todo mock precisa lidar com tudo.

### D: Dependency Inversion

```
application (TransferService)  ──depende de──▶  FraudCheckPort (interface, em application.port)
                                                        ▲
infrastructure (FraudCheckAdapter) ──implementa─────────┘
```
A regra de negócio **não depende** do Feign, do HTTP nem do Resilience4j. Consequência prática: [`TransferServiceTest`](../../services/account-service/src/test/java/br/com/pocbank/account/application/TransferServiceTest.java) roda em milissegundos, sem Spring, só com mocks.

---

## Parte 2: Design Patterns na POC

### Strategy + Resolver
**Problema:** a regra varia conforme um tipo.
[`FeePolicyResolver`](../../services/account-service/src/main/java/br/com/pocbank/account/domain/fee/FeePolicyResolver.java):
```java
public FeePolicyResolver(List<FeePolicy> policies) {
    policies.forEach(policy -> this.policies.put(policy.type(), policy));
    for (TransferType type : TransferType.values()) {
        if (!this.policies.containsKey(type)) {
            throw new IllegalStateException("Nenhuma FeePolicy registrada para " + type);
        }
    }
}
```
Dois detalhes que valem ouro:
- **`EnumMap`** é mais rápido e compacto que `HashMap` para chaves enum.
- **Fail-fast no boot:** se alguém adicionar `TransferType.TEF` e esquecer a política, a aplicação **não sobe**. É melhor que descobrir em produção com um `NullPointerException`.

### Factory Method
`Account.open`, `Transfer.completed/rejected`, `TransferCompletedEvent.of`. Visto na [Aula 02](02-modelagem-de-dominio-em-java.md).

### Parameter Object
`Transfer.Draft`. Também na Aula 02.

### Adapter + Anti-Corruption Layer (ACL)
[`FraudCheckAdapter`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/fraud/FraudCheckAdapter.java) traduz o mundo externo para o nosso:
```java
private static FraudDecision toDecision(FraudApiResponse response) {
    return response.approved()                    // externo: "APPROVED"/"DENIED" em String
            ? FraudDecision.approve(response.score())   // nosso: tipo rico
            : FraudDecision.deny(response.score(), response.reason());
}
```
Se o fornecedor mudar de `"DENIED"` para `"REJECTED"`, ou for trocado por outro, só o adapter muda. **O vocabulário do fornecedor não contamina o domínio.**

### Facade
[`design-system/index.ts`](../../frontend/src/design-system/index.ts) no front. Uma porta única que esconde a implementação dos componentes ([Aula 14](14-react-e-design-system.md)).

### Observer
[`StatementEventBus`](../../services/statement-service/src/main/java/br/com/pocbank/statement/application/StatementEventBus.java): a projeção publica, e N clientes SSE assinam. Quem publica não conhece quem assina.

### Chain of Responsibility
Os filtros HTTP (`CorrelationIdFilter` no gateway e no account-service). Cada filtro faz sua parte e chama `chain.doFilter`/`chain.filter`.

### Padrões arquiteturais
Transactional Outbox ([Aula 10](10-eventos-outbox-e-kafka.md)), Circuit Breaker ([Aula 09](09-integracoes-e-resiliencia.md)), CQRS ([Aula 07](07-nosql-e-cqrs.md)), Idempotent Receiver ([Aula 08](08-apis-rest.md) e [Aula 10](10-eventos-outbox-e-kafka.md)).

---

## Parte 3: quando NÃO usar

| Sinal de exagero | Exemplo |
|---|---|
| Interface com uma única implementação que nunca vai variar | `IUserService` + `UserServiceImpl` para tudo |
| Factory de factory | `AbstractTransferFactoryProvider` |
| Strategy para um `if` de duas linhas | Estratégia para "maior de idade" |
| Padrão escolhido antes do problema aparecer | "Vamos usar Visitor porque é elegante" |

**Por que então `FraudCheckPort` tem uma implementação só?** Porque ela isola uma **dependência externa** (rede, fornecedor). Isso facilita o teste e a troca de fornecedor. É uma fronteira real, não uma abstração decorativa.

---

## No seu ERP (Java + PostgreSQL)

### Strategy para cálculo de impostos
O caso mais clássico de ERP no Brasil:
```java
public interface CalculoTributario {
    RegimeTributario regime();
    Tributos calcular(ItemNota item, ContextoFiscal contexto);
}

class SimplesNacional implements CalculoTributario { ... }
class LucroPresumido implements CalculoTributario { ... }
class LucroReal implements CalculoTributario { ... }

class CalculoTributarioResolver {   // igual ao FeePolicyResolver, com fail-fast
    CalculoTributarioResolver(List<CalculoTributario> calculos) { ... }
}
```
Mudou a legislação de um regime? Mexe numa classe, com testes isolados dela.

### Adapter/ACL para integrações
- `EmissorNotaFiscal` (port) → `SefazSpAdapter`, `ProvedorXAdapter`.
- `GatewayCobranca` (port) → `CnabItauAdapter`, `CnabBradescoAdapter`. Cada banco com seu layout CNAB, e o financeiro só enxerga `Boleto` e `Retorno`.

### State para status de documentos
Pedido, nota e título financeiro têm ciclos de vida. Um `enum` com as transições permitidas evita um "cancelar nota já cancelada":
```java
enum StatusNota {
    DIGITACAO, AUTORIZADA, CANCELADA, DENEGADA;

    private static final Map<StatusNota, Set<StatusNota>> TRANSICOES = Map.of(
            DIGITACAO, Set.of(AUTORIZADA, DENEGADA),
            AUTORIZADA, Set.of(CANCELADA),
            CANCELADA, Set.of(),
            DENEGADA, Set.of());

    boolean podeIrPara(StatusNota destino) {
        return TRANSICOES.get(this).contains(destino);
    }
}
```

### Chain of Responsibility para validações
Validar um pedido antes de faturar: crédito do cliente, estoque, regra fiscal, preço mínimo. Cada validação numa classe (`ValidacaoPedido`), todas injetadas como `List<ValidacaoPedido>`, rodando em sequência e juntando os erros. Uma validação nova é uma classe nova (OCP).

---

## Exercícios

1. Implemente `TefFeePolicy` (tarifa fixa de R$ 2,00). Quantos arquivos você precisou alterar além de criar a classe? (Resposta esperada: `TransferType` e `ApplicationConfig`.) Como eliminar a alteração no config? Dica: `@Component`.
2. Remova o registro da `TedFeePolicy` no `ApplicationConfig` e suba a aplicação. Leia a mensagem de erro.
3. Ache no seu ERP um `switch`/`if-else` sobre tipo que já cresceu demais. Esboce a Strategy.
4. Ache uma interface com uma implementação só. Ela isola uma fronteira real, ou é decorativa?
