# Aula 09: Integrações e resiliência

**Objetivo:** entender como a POC conversa com um serviço externo que pode estar lento ou fora do ar (o antifraude), e os mecanismos para que a falha dele não derrube o nosso sistema.

---

## 1. Tudo que pode dar errado numa chamada remota

| Falha | Sintoma | Perigo |
|---|---|---|
| Serviço fora do ar | Conexão recusada, na hora | Baixo, falha rápida |
| Rede lenta ou serviço sobrecarregado | Resposta demora 30 s | **Alto**: threads e conexões presas esperando |
| Erro 500 intermitente | Às vezes funciona | Médio |
| Resposta com formato inesperado | Erro de parsing | Médio |
| Respondeu, mas a resposta se perdeu | Timeout, sem saber se processou | Alto em operações não idempotentes |

A falha mais perigosa **não é o serviço cair; é ele ficar lento.** Um serviço fora do ar falha em milissegundos. Um serviço lento prende recursos até esgotar o pool de threads, e aí o **seu** sistema para de responder, inclusive em funções que nem usam o antifraude. Isso é **falha em cascata**.

## 2. Os mecanismos de defesa

### Timeout: a defesa número 1
```yaml
spring.cloud.openfeign.client.config.fraud-api:
  connect-timeout: 1000     # quanto espera para abrir a conexão
  read-timeout: 2000        # quanto espera pela resposta
```
**Sem timeout, uma chamada pode esperar para sempre.** Muitos clientes HTTP têm timeout infinito ou muito alto por padrão. A primeira coisa a revisar em qualquer integração é: onde está o timeout?

Como escolher o valor: olhe a latência real do serviço (p99) e dê uma folga. Se o p99 do antifraude é 400 ms, 2 s é generoso. Timeout muito curto gera falsos erros; muito longo não protege.

### Retry: só com cuidado
Tentar de novo resolve falhas passageiras, mas:
- **Só em operação idempotente.** Retry de POST sem idempotência duplica.
- **Com backoff exponencial e jitter** (100 ms, 200 ms, 400 ms + aleatoriedade). Retry imediato de mil clientes ao mesmo tempo derruba de vez um serviço que estava se recuperando.
- **Com limite.** Retry em várias camadas multiplica: 3 retries no front × 3 no gateway × 3 no serviço = 27 chamadas por clique.

A POC só faz retry no gateway, só em `GET`, só em 502/503, com backoff ([Aula 13](13-gateway-observabilidade-e-seguranca.md)). No antifraude **não há retry**: a chamada é rápida de falhar, e o usuário pode tentar de novo com a mesma `Idempotency-Key`.

### Circuit Breaker: parar de insistir
Funciona como o disjuntor da sua casa:

```
        falhas ≥ 50% em 20 chamadas
FECHADO ─────────────────────────────▶ ABERTO
(chamadas passam)                     (falha na hora, sem chamar o serviço)
   ▲                                      │
   │ as 3 de teste funcionam              │ depois de 15 s
   │                                      ▼
   └────────────────────────────── MEIO-ABERTO
                                  (deixa 3 chamadas passarem para testar)
         se falharem, volta para ABERTO
```
Configuração da POC ([`application.yml`](../../services/account-service/src/main/resources/application.yml)):
```yaml
resilience4j.circuitbreaker.instances.fraud-api:
  sliding-window-size: 20                         # olha as últimas 20 chamadas
  minimum-number-of-calls: 10                     # só avalia com pelo menos 10
  failure-rate-threshold: 50                      # abre com 50% de falha
  wait-duration-in-open-state: 15s                # fica aberto 15 s
  permitted-number-of-calls-in-half-open-state: 3
```
Benefícios:
- **Para nós:** com o circuito aberto, a falha é imediata (microssegundos) em vez de esperar 2 s de timeout. Nenhum recurso fica preso.
- **Para eles:** o serviço doente para de receber carga e consegue se recuperar.

Acompanhe o estado em `http://localhost:8081/actuator/circuitbreakers`.

### Time Limiter
Um limite de tempo aplicado por fora da chamada (3 s na POC). Ele é uma rede de segurança caso o timeout do cliente HTTP falhe ou não exista. No teste real, quem cortou primeiro foi o read-timeout do Feign (2 s).

### Bulkhead (não usado, mas vale conhecer)
Limita quantas chamadas simultâneas vão para um mesmo serviço (ex.: no máximo 20 ao antifraude). O nome vem das anteparas de um navio: se um compartimento inunda, os outros continuam secos. Assim, o antifraude lento não consome todas as threads do sistema.

### Fallback e a decisão fail-open × fail-closed
Quando a chamada falha, o que fazer?

| Política | O que faz | Quando |
|---|---|---|
| **Fail-closed** | Recusa a operação | Segurança e dinheiro: a POC recusa a transferência (503) |
| **Fail-open** | Segue sem a resposta | Recurso opcional: recomendação de produto, cálculo de frete estimado |
| **Fallback degradado** | Resposta alternativa | Cache da última resposta, valor padrão, fila para processar depois |

[`FraudCheckAdapter`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/fraud/FraudCheckAdapter.java):
```java
return circuitBreaker.run(() -> toDecision(client.evaluate(toApi(request))), failure -> {
    throw new FraudServiceUnavailableException(failure);   // fail-closed
});
```
> **Essa decisão não é técnica.** Quem decide se o banco aceita transferir sem análise de fraude é o negócio, o risco e o compliance. O papel do dev é explicitar a pergunta e registrar a resposta (o [ADR 0004](../adr/0004-fraud-check-fail-closed.md) faz isso).

## 3. OpenFeign: cliente HTTP declarativo

[`FraudApiClient`](../../services/account-service/src/main/java/br/com/pocbank/account/infrastructure/fraud/FraudApiClient.java):
```java
@FeignClient(name = "fraud-api", url = "${integrations.fraud-api.url}")
public interface FraudApiClient {
    @PostMapping("/v1/fraud/evaluations")
    FraudApiResponse evaluate(@RequestBody FraudApiRequest request);
}
```
Você escreve a interface, e o Spring Cloud gera a implementação. Configuração por cliente (timeouts, log) fica no YAML.

| Cliente | Estilo | Quando |
|---|---|---|
| **OpenFeign** | Declarativo, bloqueante | Muitas integrações REST, times acostumados com Spring Cloud |
| **`RestClient`** (Spring 6.1+) | Fluente, bloqueante | O padrão atual do Spring para código bloqueante |
| **HTTP Interface** (`@HttpExchange`) | Declarativo, nativo do Spring 6 | O "Feign nativo" sem Spring Cloud |
| **`WebClient`** | Reativo | Código WebFlux |
| `RestTemplate` | Legado | Em manutenção; não usar em código novo |

## 4. A chamada remota fica fora da transação

Visto na [Aula 05](05-postgresql-transacoes-e-concorrencia.md), e vale repetir: a POC chama o antifraude **antes** de abrir a transação. Juntar I/O remoto com transação aberta é uma das principais causas de sistemas que "travam do nada" sob carga.

## 5. Testando integrações com WireMock

[`FraudCheckAdapterWireMockTest`](../../services/account-service/src/test/java/br/com/pocbank/account/infrastructure/fraud/FraudCheckAdapterWireMockTest.java) sobe um servidor HTTP falso e testa o **Feign e o Circuit Breaker reais**:
```java
fraudApi.stubFor(post("/v1/fraud/evaluations")
        .willReturn(okJson("{\"decision\":\"APPROVED\",\"score\":1}").withFixedDelay(3_000)));

assertThatThrownBy(() -> adapter.evaluate(request("10.00")))
        .isInstanceOf(FraudServiceUnavailableException.class);   // timeout → fail-closed
```
Cenários cobertos: aprovação (e o **corpo exato** enviado, com `equalToJson`), negação, erro 500 e lentidão. Mais detalhes na [Aula 12](12-testes.md).

Em ambiente local, o mesmo WireMock roda como container com regras em [`fraud-evaluations.json`](../../infra/wiremock/mappings/fraud-evaluations.json).

---

## No seu ERP (Java + PostgreSQL)

ERP brasileiro é uma coleção de integrações instáveis: SEFAZ, prefeituras (NFS-e), bancos (boleto, PIX, CNAB), transportadoras, marketplaces. Checklist por integração:

- [ ] **Tem timeout de conexão E de leitura?** Qual o valor?
- [ ] **A chamada acontece com transação aberta ou lock em registro?**
- [ ] **O que acontece se a resposta se perder?** A operação pode ser consultada ("a nota foi autorizada?") antes de reenviar?
- [ ] **Há circuit breaker ou pelo menos um limite de tentativas?**
- [ ] **A política de falha foi decidida com o negócio?**

### A SEFAZ já tem o fallback pronto: contingência
A emissão de NF-e tem modos de contingência oficiais para quando a SEFAZ está indisponível (ex.: emitir em contingência e transmitir depois). É exatamente o padrão **fallback degradado** com **processamento posterior**, definido pela legislação. Um circuit breaker aberto pode ser o gatilho para o sistema sugerir ou entrar em contingência.

### Envio assíncrono com outbox
Para integrações que não precisam de resposta imediata (enviar pedido para a transportadora, e-mail com o DANFE), não chame direto: grave numa tabela de saída na mesma transação e deixe um worker enviar com retry e backoff ([Aula 10](10-eventos-outbox-e-kafka.md)). O usuário não espera, e a falha do parceiro não vira erro na tela.

### Resilience4j em qualquer projeto Spring
Mesmo sem Spring Cloud:
```java
@CircuitBreaker(name = "sefaz", fallbackMethod = "sefazIndisponivel")
@TimeLimiter(name = "sefaz")
public CompletableFuture<Retorno> autorizar(Nota nota) { ... }
```
(Via `resilience4j-spring-boot3`; as anotações usam AOP, então valem as mesmas armadilhas de proxy do `@Transactional`.)

---

## Exercícios

1. Com o ambiente no ar, faça 12 transferências de R$ 666,99 seguidas (valor que o WireMock atrasa). Consulte `/actuator/circuitbreakers` antes e depois. A partir de qual chamada a falha passou a ser instantânea?
2. Espere 15 s e faça uma transferência normal. O circuito voltou a fechar?
3. Adicione um teste WireMock para uma resposta `200` com corpo inválido (`"not json"`). O que acontece? Deveria ser fail-closed?
4. Mude a política para: "abaixo de R$ 100, aprovar sem antifraude se ele estiver fora; acima, recusar". Onde você colocaria essa regra: no adapter ou no caso de uso? Por quê?
5. Revise a integração mais instável do seu ERP com o checklist acima.
