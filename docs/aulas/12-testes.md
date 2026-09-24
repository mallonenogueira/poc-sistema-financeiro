# Aula 12: Testes (JUnit 5, Mockito, WireMock e companhia)

**Objetivo:** entender a estratégia de testes da POC, cada ferramenta usada, as técnicas que tornaram o código testável e (importante) o que **não** foi testado.

---

## 1. A pirâmide

```
            ▲  poucos, lentos, frágeis
           ╱ ╲      E2E (manual na POC: roteiro do README)
          ╱───╲
         ╱     ╲    Integração: WireMock + Feign real, @WebMvcTest, WebTestClient
        ╱───────╲
       ╱         ╲  Unitários: domínio, casos de uso com Mockito
      ╱───────────╲
     ▼  muitos, rápidos, estáveis
```
| Camada | Quantidade na POC | Tempo típico |
|---|---|---|
| Unitários (domínio + aplicação + handlers) | ~35 | milissegundos |
| Integração/fatia (WireMock, MVC, router) | ~15 | segundos |
| Front (Vitest + Testing Library) | 8 | ~1 s |

## 2. JUnit 5: recursos usados

### Testes parametrizados
[`FeePolicyTest`](../../services/account-service/src/test/java/br/com/pocbank/account/domain/FeePolicyTest.java):
```java
@ParameterizedTest(name = "{0} de {1} custa {2}")
@CsvSource({
        "PIX, 10.00,     0.00",
        "TED, 100.00,    5.10",
        "TED, 1000.00,   6.00",
        "TED, 20000.00,  25.00",     // atingiu o teto
        "TED, 1000000,   25.00"
})
void calculatesFeeByTransferType(TransferType type, BigDecimal amount, BigDecimal expectedFee) {
    assertThat(resolver.resolve(type).calculate(amount)).isEqualByComparingTo(expectedFee);
}
```
A regra de negócio vira uma **tabela legível**, que dá até para mostrar ao analista de negócio. O JUnit converte as strings para enum e `BigDecimal` sozinho.

### `@Nested` para agrupar cenários
[`TransferServiceTest`](../../services/account-service/src/test/java/br/com/pocbank/account/application/TransferServiceTest.java):
```java
@Nested
class WhenFraudApproves {
    @BeforeEach
    void approve() { when(fraudCheck.evaluate(any())).thenReturn(FraudDecision.approve(10)); }

    @Test void movesMoneyAndChargesFee() { ... }
    @Test void publishesCompletedEventAfterPersisting() { ... }
}
```
O `@BeforeEach` interno só vale para o grupo, e o relatório fica hierárquico.

### Nomes que descrevem comportamento
`insufficientFundsAbortsWithoutPublishing`, `fraudDenialPersistsRejectedTransferWithoutMovingMoney`. Quando o teste falha no CI, o nome já diz **qual regra quebrou**. Compare com `testTransfer2()`.

### AssertJ
```java
assertThat(account.getBalance()).isEqualByComparingTo("69.50");   // BigDecimal por valor
assertThatThrownBy(() -> account.debit(...)).isInstanceOf(InsufficientFundsException.class);
assertThat(csv.lines()).containsExactly(HEADER, "2026-09-23T12:00:00Z,t1,CREDIT,...");
```
É mais legível que `assertEquals`, e as mensagens de falha dizem o que era esperado e o que veio.

## 3. Mockito

| Recurso | Para quê | Exemplo na POC |
|---|---|---|
| `@Mock` + `@ExtendWith(MockitoExtension.class)` | Cria dublês | Repositórios, `FraudCheckPort` |
| `when(...).thenReturn(...)` | Define resposta | Antifraude aprova ou nega |
| `thenAnswer(inv -> inv.getArgument(0))` | Resposta calculada | `saveAndFlush` devolve o que recebeu |
| `thenThrow` | Simula falha | Antifraude fora do ar |
| `verify(mock, never())` | Garante que algo **não** aconteceu | Não publica evento sem saldo |
| `verifyNoInteractions` | Nenhuma chamada | Idempotência não chama o antifraude |
| `ArgumentCaptor` | Inspeciona o que foi passado | Conteúdo do evento publicado |
| `InOrder` | Garante a ordem | Salva antes de publicar; locks em ordem |
| `lenient()` | Stub que nem todo teste usa | Setup comum no `@BeforeEach` |

Por que `lenient()`? Por padrão (strict stubs), o Mockito **falha** se um stub não for usado, para detectar setup morto. No `@BeforeEach` compartilhado, alguns testes não usam todos os stubs, então eles são marcados como lenientes.

### O que mockar e o que não mockar
- ✔ **Mocke fronteiras:** repositórios, clientes HTTP, publicadores, relógio.
- ✘ **Não mocke o domínio:** o teste usa `Account` e `FeePolicy` **reais**. Mockar `account.debit()` testaria só o mock.
- ✘ **Não mocke o que você não controla sem um teste de integração junto** (ex.: o Feign); aí entra o WireMock.

> Excesso de mock produz testes que passam com o sistema quebrado e quebram a cada refatoração. Se um teste tem 15 `when(...)`, a classe testada provavelmente faz coisa demais.

## 4. Técnicas que tornaram o código testável

**Relógio injetado:**
```java
Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC)
```
Todo timestamp é previsível e dá para comparar exatamente.

**Transação injetada:**
```java
TransactionOperations.withoutTransaction()   // executa o callback direto, sem banco
```
O `TransferService` recebe `TransactionOperations` em vez de usar `@Transactional`. No teste, a transação vira "execute isto". Não precisa de Spring nem banco.

**Portas no lugar de clientes concretos** ([Aula 04](04-arquitetura-hexagonal.md)): mockar `FraudCheckPort` é trivial; mockar um cliente Feign com Resilience4j seria um pesadelo.

## 5. WireMock: testando a integração HTTP de verdade

[`FraudCheckAdapterWireMockTest`](../../services/account-service/src/test/java/br/com/pocbank/account/infrastructure/fraud/FraudCheckAdapterWireMockTest.java):
```java
@RegisterExtension
static WireMockExtension fraudApi = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())       // porta livre aleatória: sem conflito no CI
        .build();

@DynamicPropertySource
static void fraudApiUrl(DynamicPropertyRegistry registry) {
    registry.add("integrations.fraud-api.url", fraudApi::baseUrl);   // aponta o Feign para o WireMock
}
```
O que ele testa que o Mockito não testa:
- a **serialização real** do request (`equalToJson` confere o corpo exato enviado);
- o **mapeamento real** da resposta;
- **timeouts reais** (`withFixedDelay`) e erros HTTP reais (500);
- a **configuração** do Feign e do Circuit Breaker.

### Contexto Spring mínimo
```java
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class, KafkaAutoConfiguration.class})
@EnableFeignClients(clients = FraudApiClient.class)
@Import(FraudCheckAdapter.class)
static class FeignOnlyApp { }
```
Sobe só o necessário para o Feign (sem banco, Kafka nem Flyway). Isso deixa o teste rápido e independente de infraestrutura.

### A história do teste instável (flaky)
Na primeira execução, o teste do "caminho feliz" **falhou**: a primeira requisição da JVM (classes carregando, conexão abrindo) passou dos 500 ms do read-timeout configurado no teste. A correção foi dar folga (1.500 ms) e aumentar o atraso do cenário de lentidão (3.000 ms) para manter uma margem clara entre "rápido" e "lento".

> Lição: timeouts em teste precisam de **margem grande** entre o caso que deve passar e o que deve falhar. CI é mais lento que a sua máquina.

## 6. Testes de fatia (slice)

**`@WebMvcTest`** ([`TransferControllerTest`](../../services/account-service/src/test/java/br/com/pocbank/account/web/TransferControllerTest.java)): sobe só a camada web (controllers, advice, conversores, validação), com o service mockado. Testa:
- status codes, headers (`Location`), JSON de resposta;
- que o header `Idempotency-Key` é obrigatório;
- a tradução de exceções em ProblemDetail.

> Por isso a `IntegrationConfig` (Feign, `@EnableScheduling`) fica **fora** da classe `@SpringBootApplication`: se estivesse nela, o `@WebMvcTest` tentaria subir o Feign e o agendador.

`@MockBean` foi descontinuado no Spring Boot 3.4 em favor de `@MockitoBean`. A POC usa Boot 3.3, então `@MockBean` ainda é o correto aqui.

**`WebTestClient` com router** ([`StatementRouterTest`](../../services/statement-service/src/test/java/br/com/pocbank/statement/web/StatementRouterTest.java)): `bindToRouterFunction` testa as rotas funcionais **sem subir o Spring**.

## 7. Testando código reativo: `StepVerifier`

```java
StepVerifier.create(handler().handle(message)).verifyComplete();

StepVerifier.create(handler().handle(...)).verifyError(IllegalStateException.class);

StepVerifier.create(bus.stream("ana").take(1))
        .then(() -> projection.apply(event).subscribe())   // dispara depois de assinar
        .assertNext(debit -> assertThat(debit.id()).isEqualTo("t1:DEBIT"))
        .expectComplete()
        .verify(Duration.ofSeconds(2));                    // sempre com timeout!
```
Nunca teste reativo com `.block()` e asserts soltos: você perde a verificação de "completou" e "deu erro", e testes de fluxo infinito travam para sempre.

## 8. Testes do front

[`TransferForm.test.tsx`](../../frontend/src/features/transfers/TransferForm.test.tsx) com Testing Library:
```ts
await user.selectOptions(screen.getByLabelText('Conta de origem'), 'a');
await user.click(screen.getByRole('button', { name: 'Transferir' }));
expect(await screen.findByRole('alert')).toHaveTextContent('Tente novamente');
```
A filosofia: **testar como o usuário usa** (pelo texto do label, pelo papel do elemento), não pela implementação (classe CSS, estado interno). Bônus: se `getByLabelText` encontra o campo, o label está corretamente associado, e isso é um teste de acessibilidade de graça.

## 9. O que NÃO foi testado (lacunas reais)

| Lacuna | Risco | Como cobrir |
|---|---|---|
| **Queries e locks no Postgres real** | `findByIdForUpdate`, `SKIP LOCKED`, constraints: nada disso é exercitado. A ordem de locks foi testada só com mock | **Testcontainers** |
| **Concorrência** | Nenhum teste dispara transferências simultâneas | Teste com `ExecutorService` + Testcontainers |
| **Outbox → Kafka → Mongo** | Só testado manualmente | Testcontainers com Kafka e Mongo |
| **Contrato entre produtor e consumidor do evento** | O produtor pode mudar o JSON e quebrar o consumidor sem nenhum teste falhar | Contract testing (Pact, Spring Cloud Contract) ou schema registry |
| **E2E** | Roteiro manual | Playwright contra o compose |

### Como seria o teste de concorrência com Testcontainers
```java
@SpringBootTest
@Testcontainers
class ConcurrentTransfersIT {

    @Container
    @ServiceConnection                     // Spring Boot 3.1+: configura o datasource sozinho
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TransferService service;
    @Autowired AccountRepository accounts;
    @MockBean FraudCheckPort fraudCheck;   // o Kafka e o relay também precisariam ser isolados

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        when(fraudCheck.evaluate(any())).thenReturn(FraudDecision.approve(1));
        // Ana tem 5000; 100 transferências de 100 em paralelo: só 50 podem passar
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<?>> futures = IntStream.range(0, 100).mapToObj(i -> pool.submit(() ->
                service.execute(new TransferCommand(ANA, BRUNO, new BigDecimal("100"), PIX, "k" + i))))
                .toList();
        long failures = futures.stream().filter(f -> threwInsufficientFunds(f)).count();

        assertThat(accounts.findById(ANA).orElseThrow().getBalance()).isEqualByComparingTo("0.00");
        assertThat(failures).isEqualTo(50);
    }
}
```
Esse é o teste que realmente prova que a estratégia de lock da [Aula 05](05-postgresql-transacoes-e-concorrencia.md) funciona.

---

## No seu ERP (Java + PostgreSQL)

1. **Cálculo fiscal com `@CsvSource`:** cada linha é um caso (NCM, UF de origem e destino, CFOP, valor → ICMS esperado). O contador valida a tabela, e o teste garante que ninguém quebra o cálculo.
   ```java
   @ParameterizedTest
   @CsvSource({
       "SP, SP, 5102, 1000.00, 180.00",
       "SP, RJ, 6102, 1000.00, 120.00",
   })
   void calculaIcms(String ufOrigem, String ufDestino, String cfop, BigDecimal base, BigDecimal esperado) { ... }
   ```
2. **Testcontainers com Postgres real** para tudo que depende de SQL: queries nativas, locks, constraints, triggers, funções. H2 "em modo Postgres" **não** é o Postgres e engana (sem `SKIP LOCKED`, `JSONB` e comportamento de lock diferente).
3. **`Clock` injetado** para fechamento de mês, vencimentos, juros, prazos fiscais. Teste "último dia do mês", "ano bissexto", "virada do ano".
4. **WireMock para SEFAZ, bancos e transportadoras:** grave respostas reais (sem dados sensíveis) como fixtures, e teste rejeições, timeouts e XML inesperado.
5. **Comece pelo que dá mais medo de mexer.** Não tente cobrir o ERP inteiro. Escreva testes antes de alterar uma regra crítica (*characterization tests*: documentam o comportamento atual, mesmo que estranho).

---

## Exercícios

1. Rode os testes e abra o relatório de cobertura: `target/site/jacoco/index.html` do account-service. Qual pacote tem menos cobertura? Faz sentido?
2. Quebre de propósito a ordem dos locks no `TransferService` (remova o `.sorted()`). Qual teste falha?
3. Adicione Testcontainers ao account-service e implemente o `ConcurrentTransfersIT`. Depois remova o `@Lock` do repositório e rode de novo.
4. Escreva um teste WireMock para o antifraude respondendo 200 com `{"decision":"MAYBE"}`. Qual deveria ser o comportamento?
5. No ERP, escolha uma regra de cálculo e escreva um `@ParameterizedTest` com 10 casos reais.
