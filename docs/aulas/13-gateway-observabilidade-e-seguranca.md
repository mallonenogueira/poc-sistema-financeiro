# Aula 13: API Gateway, observabilidade e segurança

**Objetivo:** entender o papel do gateway, como uma requisição é rastreada entre serviços, o que o Actuator expõe e (a maior lacuna da POC) a segurança.

---

## 1. Spring Cloud Gateway

Não foi escrito do zero: é um produto do Spring configurado quase todo em YAML ([`application.yml`](../../services/api-gateway/src/main/resources/application.yml)).

```yaml
routes:
  - id: accounts
    uri: ${ACCOUNT_SERVICE_URL:http://localhost:8081}
    predicates:
      - Path=/api/accounts/**,/api/transfers/**
    filters:
      - name: Retry
        args: { retries: 2, methods: GET, statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE, ... }
  - id: statements
    uri: ${STATEMENT_SERVICE_URL:http://localhost:8082}
    predicates:
      - Path=/api/statements/**
```
| Conceito | O que é |
|---|---|
| **Route** | Destino + condições + filtros |
| **Predicate** | Condição para a rota casar (caminho, método, header, horário) |
| **Filter** | Transforma a requisição ou a resposta (adiciona header, retry, rate limit, reescreve caminho) |
| **GlobalFilter** | Filtro aplicado a todas as rotas (o `CorrelationIdFilter`) |

O gateway roda sobre WebFlux (Netty): ele só repassa bytes, e por isso o modelo reativo cai muito bem.

### Para que ele serve e quando é dispensável
Já discutido na conversa que gerou este material, e vale registrar:
- ✔ Um endereço único para o front; CORS, correlation-id e retry num lugar só; os serviços de domínio fechados atrás dele (NetworkPolicy).
- ✘ Em empresas com gateway corporativo (AWS API Gateway, Apigee, Kong) e Ingress no Kubernetes, ele pode ser só mais um salto de rede.
- Se justifica como **BFF** (Backend for Frontend: agrega chamadas sob medida para uma tela) ou quando há lógica de borda que o gateway corporativo não cobre.

### O bug do header duplicado
No teste real, `X-Correlation-Id` voltou **duas vezes** na resposta: o gateway colocou o dele, e o account-service devolveu o mesmo header, que o gateway repassou. A correção foi o filtro `DedupeResponseHeader=... X-Correlation-Id, RETAIN_FIRST`. Proxies em cadeia costumam duplicar headers (CORS é o caso clássico).

## 2. Correlation-id: seguindo uma requisição

```
Cliente ──▶ Gateway ──────────────▶ account-service ─────────▶ antifraude
            gera X-Correlation-Id    MDC.put("correlationId")
            (ou reaproveita o do     logs: [account-service,5d39d00d-...]
             cliente)
            devolve na resposta
```
- [`CorrelationIdFilter` do gateway](../../services/api-gateway/src/main/java/br/com/pocbank/gateway/CorrelationIdFilter.java): gera se não vier, propaga para o serviço e devolve ao cliente.
- [`CorrelationIdFilter` do account-service](../../services/account-service/src/main/java/br/com/pocbank/account/web/CorrelationIdFilter.java): coloca no **MDC** (Mapped Diagnostic Context do SLF4J) e **remove no `finally`**. Sem o `remove`, com pool de threads, o id de uma requisição vazaria para a próxima que usasse a mesma thread.
- O padrão de log inclui o MDC: `%5p [${spring.application.name},%X{correlationId:-}]`.

O usuário reclama de um erro, informa o id que aparece na tela ou no header, e você acha **todos os logs daquela operação** em todos os serviços.

### Lacunas
- O id **não atravessa o Kafka**: o evento não leva o correlation-id num header, e os logs do statement-service ficam desconectados da requisição original.
- O statement-service (reativo) não tem o MDC configurado (a [Aula 11](11-programacao-reativa-webflux-sse.md) explica por que é mais difícil).
- A chamada Feign ao antifraude não propaga o header.

### A solução completa: tracing distribuído
**Micrometer Tracing + OpenTelemetry** fazem isso automaticamente: geram `traceId`/`spanId`, propagam por HTTP (header `traceparent`, padrão W3C) **e por Kafka**, colocam no MDC e exportam para um backend (Jaeger, Tempo, Zipkin, X-Ray, Datadog) que desenha a linha do tempo da requisição entre os serviços. O correlation-id manual da POC é a versão didática disso.

## 3. Os três pilares da observabilidade

| Pilar | Pergunta | Na POC |
|---|---|---|
| **Logs** | O que aconteceu nesta operação? | SLF4J + correlation-id |
| **Métricas** | Como o sistema está, em números? | Actuator + Micrometer + `/actuator/prometheus` |
| **Traces** | Por onde a requisição passou e onde demorou? | Não implementado (seção acima) |

### Actuator
```yaml
management.endpoints.web.exposure.include: health,info,prometheus,circuitbreakers
management.endpoint.health.probes.enabled: true
```
- `/actuator/health/liveness` e `/readiness`: usados pelos probes do Kubernetes ([Aula 15](15-docker-e-kubernetes.md)).
- `/actuator/prometheus`: métricas HTTP (latência por endpoint e status), JVM (heap, GC, threads), Hikari (conexões em uso e em espera), Resilience4j (estado do circuito).
- `/actuator/circuitbreakers`: estado do circuito do antifraude.

Métricas de negócio que um sistema real teria (não implementadas):
```java
meterRegistry.counter("transfers.completed", "type", transfer.getType().name()).increment();
meterRegistry.counter("transfers.rejected", "reason", "fraud").increment();
```

> Cuidado: **não exponha o Actuator na internet.** Endpoints como `env` e `heapdump` vazam segredos. Em produção, porta separada (`management.server.port`) acessível só internamente.

## 4. Segurança: a maior lacuna da POC

**A POC não tem autenticação nem autorização.** Qualquer pessoa que alcance a API pode:
- listar todas as contas;
- transferir **de qualquer conta** para qualquer conta;
- ler o extrato de qualquer um.

Isso tem nome: **BOLA/IDOR** (Broken Object Level Authorization), o item número 1 do OWASP API Security Top 10. Numa entrevista para o setor financeiro, é o primeiro ponto a admitir.

### Como seria
```
Usuário ──login──▶ Identity Provider (Keycloak, Cognito, Okta)
        ◀──── JWT (access token: sub=usuario-123, scope=transfers:write, exp=15min)

Cliente ──Authorization: Bearer <jwt>──▶ Gateway (valida assinatura e expiração)
                                        ──▶ account-service (Resource Server: valida de novo e autoriza)
```
1. **Autenticação (quem é você):** OAuth2/OpenID Connect. O front usa o fluxo *Authorization Code + PKCE*. Tokens de vida curta.
2. **No gateway:** `spring-boot-starter-oauth2-resource-server` rejeita requisição sem token válido. Em muitas empresas, isso fica no gateway corporativo.
3. **Nos serviços, também:** cada serviço valida o JWT (zero trust: não confiar só porque veio da rede interna).
4. **Autorização por objeto, que o gateway não sabe fazer:**
   ```java
   // o dono da conta de origem precisa ser o usuário autenticado
   Account source = accounts.findById(command.sourceAccountId()).orElseThrow(...);
   if (!source.getOwnerId().equals(currentUser.id())) {
       throw new AccessDeniedException(...);    // 403, ou 404 para não revelar que a conta existe
   }
   ```
   Isso é **regra de negócio** e precisa ter teste.
5. **Complementos:** rate limiting (por usuário e por IP), limites transacionais (valor por dia, por horário), step-up auth (pedir biometria ou senha acima de certo valor), HTTPS/mTLS entre serviços, segredos no Secrets Manager (a POC tem senha `pocbank` no compose e no overlay de dev; aceitável só em dev).

### O que a POC fez de segurança
- CPF mascarado na resposta (LGPD, minimização de dados).
- Nada de CPF ou saldo em log.
- Containers sem root, sistema de arquivos read-only, Pod Security `restricted`, NetworkPolicy.
- IAM de menor privilégio para a Lambda e IRSA para o S3.
- Lifecycle no S3 (retenção limitada dos exports).
- ExternalSecret em prod (senha fora do Git).

---

## No seu ERP (Java + PostgreSQL)

- **Correlation-id hoje, com 30 linhas:** um filtro que gera o id, coloca no MDC com `usuario`, `empresa`/`tenant` e `modulo`, e remove no `finally`. Suporte a produção muda de patamar: "manda o código que apareceu no erro" e você acha tudo.
- **Logs estruturados em JSON** (Logback com `logstash-logback-encoder`, ou o suporte nativo do Spring Boot 3.4+). Buscar `correlationId=abc AND level=ERROR` num agregador de logs é outro mundo comparado a `grep`.
- **Métricas que importam num ERP:** conexões do Hikari em espera (sinal de pool esgotado ou transação longa), tempo das queries mais usadas, tamanho das filas de job/outbox, erros por integração (SEFAZ, bancos).
- **Autorização por objeto** é o bug de segurança mais comum em ERP: usuário da empresa A acessando o pedido da empresa B trocando o id na URL. Toda consulta por id precisa filtrar pelo tenant e pelo escopo do usuário. Considere **Row Level Security** do Postgres como segunda barreira:
  ```sql
  ALTER TABLE vendas.pedido ENABLE ROW LEVEL SECURITY;
  CREATE POLICY por_empresa ON vendas.pedido
      USING (empresa_id = current_setting('app.empresa_id')::bigint);
  -- a aplicação faz SET app.empresa_id = ? no início de cada transação
  ```
- **Auditoria** de quem fez o quê (usuário, data, antes/depois) nas entidades fiscais e financeiras.

---

## Exercícios

1. Faça uma transferência com `curl -H 'X-Correlation-Id: meu-teste-123'` e encontre esse id nos logs do account-service.
2. Acesse `http://localhost:8081/actuator/prometheus` e ache: a latência do `POST /api/transfers`, as conexões ativas do Hikari e o estado do circuit breaker.
3. Liste 5 ataques possíveis contra a POC do jeito que ela está hoje.
4. Adicione `spring-boot-starter-oauth2-resource-server` ao account-service com um JWT de teste (Spring Security Test: `.with(jwt())`) e proteja o `POST /api/transfers`.
5. No ERP, escolha um endpoint que recebe id na URL e verifique se ele confere se o registro pertence à empresa ou ao usuário logado.
