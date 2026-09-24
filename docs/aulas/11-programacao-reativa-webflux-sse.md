# Aula 11: Programação reativa, WebFlux e SSE

**Objetivo:** entender o modelo reativo usado no statement-service, os operadores do Reactor que aparecem no código, como funciona o extrato em tempo real (SSE) e quando o reativo **não** vale a pena.

---

## 1. O problema: threads esperando

No modelo tradicional (Spring MVC + Tomcat), **cada requisição ocupa uma thread** do início ao fim:
```
thread-1: recebe → consulta Mongo (espera 20 ms) → serializa → responde
```
Durante os 20 ms de espera a thread está **parada**, mas continua ocupada. Com 200 threads, o servidor atende no máximo 200 requisições simultâneas, mesmo com a CPU ociosa.

Agora pense no extrato em tempo real: cada cliente com a tela aberta mantém **uma conexão aberta por minutos ou horas**. Mil clientes = mil threads presas sem fazer nada.

## 2. Duas respostas modernas

### Resposta A: modelo reativo (WebFlux, statement-service)
Poucas threads (≈ número de núcleos) num **event loop**. Nenhuma espera bloqueia: a thread registra "quando o Mongo responder, faça X" e vai atender outra coisa.

### Resposta B: virtual threads (Java 21, account-service)
```yaml
spring.threads.virtual.enabled: true
```
O código continua **bloqueante e simples**, mas cada requisição roda numa thread virtual, que custa poucos KB. Quando ela bloqueia em I/O, a JVM a "estaciona" e libera a thread real do sistema operacional. Dá para ter centenas de milhares.

### Comparação
| | Reativo (WebFlux) | Virtual threads (MVC) |
|---|---|---|
| Estilo de código | Pipelines `Mono`/`Flux` | Imperativo comum |
| Curva de aprendizado | Alta | Nenhuma |
| Debug e stack traces | Difíceis | Normais |
| Backpressure | Nativo | Não |
| Streaming (SSE, WebSocket) | Excelente | Funciona (`SseEmitter`) |
| JPA/JDBC | **Não combina** (são bloqueantes) | Combina |
| Ecossistema | Precisa de drivers reativos (R2DBC, Mongo reactive) | Qualquer biblioteca |

**Por que cada serviço usou um?** O account-service depende de JPA e transações: virtual threads dão escala sem abandonar o JDBC. O statement-service tem streaming (Kafka → Mongo → SSE) e driver reativo disponível: pipeline reativo de ponta a ponta. A escolha segue o problema ([ADR 0003](../adr/0003-webflux-for-statements.md)).

> Desde o Java 21, **a maioria dos sistemas CRUD não precisa de reativo** para escalar. O reativo continua forte em streaming, gateways e processamento de eventos com backpressure.

## 3. Mono e Flux

| Tipo | Emite | Analogia |
|---|---|---|
| `Mono<T>` | 0 ou 1 valor, depois termina | `Optional`/`CompletableFuture` preguiçoso |
| `Flux<T>` | 0 a N valores (possivelmente infinitos) | `Stream` assíncrono |

**Regra de ouro: nada acontece até alguém assinar (`subscribe`).** Montar um `Mono` é descrever a receita. O WebFlux assina quando vai responder a requisição; o consumidor Kafka chama `.subscribe()` explicitamente.

## 4. Os operadores que aparecem na POC

```java
// StatementProjection: salva dois documentos e publica cada um no bus
return repository.saveAll(List.of(debit, credit))   // Flux<StatementEntry>
        .doOnNext(bus::publish)                     // efeito colateral para cada item
        .then();                                    // Mono<Void>: "terminou", sem valor
```
| Operador | O que faz | Onde |
|---|---|---|
| `map` | Transforma cada valor (síncrono) | `StatementHandler.stream` |
| `flatMap` | Transforma em outro `Mono`/`Flux` e achata; **concorrente, sem ordem** | `StatementExportService` |
| `concatMap` | Igual ao `flatMap`, mas **um por vez, em ordem** | Consumidor Kafka |
| `doOnNext` | Efeito colateral sem alterar o fluxo | Projeção |
| `then` | Ignora os valores e sinaliza o término | Projeção, handler |
| `collectList` | Junta um `Flux` em `Mono<List>` | Export (carrega tudo em memória!) |
| `filter` | Filtra | `StatementEventBus.stream` |
| `merge` | Intercala dois fluxos | Lançamentos + heartbeat do SSE |
| `interval` | Emite um tick periódico | Heartbeat |
| `defer` | Adia a criação até a assinatura (cada retry cria um novo) | Consumidor Kafka |
| `retryWhen` | Reassina com política de retry | Consumidor Kafka |
| `fromFuture` | Adapta `CompletableFuture` (SDK AWS) | Export S3 |

### `flatMap` × `concatMap`: a diferença que causa bug
```java
Flux.just(1, 2, 3).flatMap(this::salvar)    // os 3 salvam em paralelo; terminam em qualquer ordem
Flux.just(1, 2, 3).concatMap(this::salvar)  // salva 1, espera terminar, salva 2...
```
No consumidor Kafka, `flatMap` quebraria a ordem da partição e confirmaria offsets fora de ordem.

## 5. Backpressure

Se o produtor é mais rápido que o consumidor, alguém precisa ceder. No Reactor, **o assinante pede quantos itens aguenta** (`request(n)`), e o produtor respeita.

Na POC:
- **Kafka → Mongo:** o Reactor Kafka só busca mais mensagens quando o `concatMap` pede. Se o Mongo ficar lento, o consumo desacelera sozinho, sem estourar memória.
- **Projeção → clientes SSE:** o [`StatementEventBus`](../../services/statement-service/src/main/java/br/com/pocbank/statement/application/StatementEventBus.java) usa `Sinks.many().multicast().directBestEffort()`. Se um cliente SSE for lento, **ele perde itens**, mas não trava a projeção nem os outros clientes. É uma escolha consciente: o cliente lento ainda tem o extrato persistido quando recarregar.

| Estratégia de sink | Comportamento com assinante lento |
|---|---|
| `directBestEffort` | Descarta para ele, segue para os outros |
| `directAllOrNothing` | Descarta para todos |
| `onBackpressureBuffer` | Bufferiza (risco de memória) |
| `replay` | Guarda histórico para quem assinar depois |

## 6. Router functions × controllers

[`StatementRouter`](../../services/statement-service/src/main/java/br/com/pocbank/statement/web/StatementRouter.java):
```java
return route(GET("/api/statements/{accountId}/stream"), handler::stream)
        .andRoute(GET("/api/statements/{accountId}"), handler::list)
        .andRoute(POST("/api/statements/{accountId}/exports"), handler::export);
```
É o estilo **funcional** do WebFlux: as rotas são dados, e é fácil testar com `WebTestClient.bindToRouterFunction` sem subir o Spring. `@RestController` com `Mono`/`Flux` também funciona no WebFlux; a POC usou os dois estilos (MVC anotado no account-service, funcional aqui) para mostrar ambos.

Detalhe: a ordem importa. `/stream` vem antes de `/{accountId}` para não ser capturado como um id.

## 7. SSE: Server-Sent Events

| | Polling | SSE | WebSocket |
|---|---|---|---|
| Direção | Cliente pergunta | Servidor → cliente | Bidirecional |
| Protocolo | HTTP comum | HTTP comum (`text/event-stream`) | Protocolo próprio (upgrade) |
| Reconexão | — | **Automática** no navegador | Manual |
| Passa por proxy/firewall | Sim | Sim (com buffering desligado) | Às vezes dá trabalho |
| Bom para | Atualizações raras | Notificações, feeds, progresso | Chat, jogos, colaboração |

O extrato só precisa **empurrar** do servidor para o cliente, então SSE é o suficiente e mais simples.

Formato no fio:
```
id:63e0...:CREDIT
event:statement-entry
data:{"id":"63e0...:CREDIT","amount":7.77,...}

:keep-alive
```
[`StatementHandler.stream`](../../services/statement-service/src/main/java/br/com/pocbank/statement/web/StatementHandler.java) junta os lançamentos com um **heartbeat** a cada 15 s. Sem o heartbeat, proxies e load balancers derrubam a conexão parada (muitos têm timeout de ociosidade de 60 s).

Cuidados de infraestrutura (todos aplicados na POC):
- nginx: `proxy_buffering off` e `proxy_read_timeout 1h` ([`nginx.conf`](../../frontend/nginx.conf));
- Ingress: anotações equivalentes ([`ingress.yaml`](../../k8s/base/ingress.yaml)).

No navegador, é nativo:
```ts
const source = new EventSource(`/api/statements/${accountId}/stream`);
source.addEventListener('statement-entry', (e) => onEntry(JSON.parse(e.data)));
```

## 8. Armadilhas do reativo

1. **Chamar código bloqueante dentro do pipeline** (JDBC, `Thread.sleep`, `.block()`, SDK síncrono). Isso trava o event loop, que atende **todas** as requisições. Se for inevitável, isole com `.subscribeOn(Schedulers.boundedElastic())`. O BlockHound detecta isso em teste.
2. **Esquecer de assinar:** o `Mono` montado e nunca retornado ou assinado simplesmente não executa. Nenhum erro, nada acontece.
3. **`ThreadLocal`/MDC não funcionam** do jeito tradicional, porque a execução troca de thread. O correlation-id nos logs precisa de *context propagation* (Micrometer). A POC **não** fez isso no statement-service.
4. **Stack traces enormes e pouco úteis.** Ajudam `checkpoint("descrição")` e `Hooks.onOperatorDebug()` (só em dev).

## 9. Autocrítica: SSE com várias réplicas

O `StatementEventBus` vive **na memória de cada instância**. Com 2 réplicas no Kubernetes (e é o que o manifest define):
- o Kafka divide as partições entre as réplicas A e B (mesmo consumer group);
- o cliente SSE está conectado na réplica A;
- o evento da transferência dele cai numa partição da réplica B;
- **a réplica A nunca fica sabendo, e o cliente não recebe o lançamento em tempo real.**

Funciona no docker compose só porque lá existe uma réplica. Correções possíveis:
- cada instância consome o tópico com um **consumer group próprio** só para notificações (todas recebem tudo; a projeção continua no grupo compartilhado);
- **pub/sub externo** (Redis Pub/Sub) entre as instâncias;
- **MongoDB Change Streams**: cada instância observa as inserções na coleção.

Este é um erro clássico de sistemas distribuídos: **estado em memória + várias instâncias**.

---

## No seu ERP (Java + PostgreSQL)

- **Você não precisa migrar para WebFlux.** Se o ERP está em Java 21 com Spring Boot 3.2+, ligar virtual threads (`spring.threads.virtual.enabled=true`) pode aumentar a capacidade de telas e APIs sem mudar código. Teste com carga antes: bibliotecas antigas que usam `synchronized` com I/O podem "prender" a thread real (*pinning*; o Java 24 resolveu boa parte disso).
- **SSE para processos longos:** importação de planilha, fechamento de mês, cálculo de folha, geração de SPED. Em vez de a tela ficar girando ou fazendo polling, o servidor empurra o progresso. No Spring MVC:
  ```java
  @GetMapping(path = "/importacoes/{id}/progresso", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter progresso(@PathVariable Long id) {
      SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
      importacaoService.assinar(id, p -> emitter.send(SseEmitter.event().name("progresso").data(p)));
      return emitter;
  }
  ```
  E lembre da armadilha da seção 9: com vários servidores, o progresso precisa passar por algo compartilhado (a própria tabela do job + polling curto no servidor, ou `LISTEN/NOTIFY` do Postgres).
- **Postgres `LISTEN/NOTIFY`** é um pub/sub embutido: um trigger faz `NOTIFY`, e todos os servidores que fizeram `LISTEN` recebem. Resolve o problema de várias instâncias sem Redis.

---

## Exercícios

1. Com o ambiente no ar, abra dois terminais com `curl -N localhost:8080/api/statements/2222.../stream` e faça uma transferência. Os dois recebem?
2. Troque `concatMap` por `flatMap` no consumidor. O que pode dar errado? Escreva um teste que mostre a diferença de ordem.
3. Coloque um `Thread.sleep(2000)` dentro do `StatementHandler.list` e faça 20 requisições simultâneas. Compare com o mesmo `sleep` num endpoint do account-service (virtual threads).
4. Suba 2 réplicas do statement-service no compose (`docker compose up -d --scale statement-service=2`, removendo a porta fixa) e reproduza o bug da seção 9.
5. No ERP, identifique um processo longo em que o usuário hoje fica sem feedback. Esboce o endpoint SSE.
