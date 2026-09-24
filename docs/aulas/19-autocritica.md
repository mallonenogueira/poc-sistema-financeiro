# Aula 19: Autocrítica, o que está errado na POC

**Objetivo:** consolidar os defeitos, as lacunas e os atalhos da POC, cada um com o problema, o impacto e a correção. Esta é a aula mais útil para entrevista: saber **criticar o próprio sistema** é o que diferencia um sênior.

Use como lista de exercícios: cada item é uma tarefa de implementação.

---

## Críticos (num banco real, bloqueariam o lançamento)

### 1. Não há autenticação nem autorização
- **Problema:** qualquer um transfere de qualquer conta e lê qualquer extrato (BOLA/IDOR, item 1 do OWASP API Top 10).
- **Correção:** OAuth2/OIDC, Resource Server nos serviços e verificação de que a conta de origem pertence ao usuário. → [Aula 13](13-gateway-observabilidade-e-seguranca.md)

### 2. A tarifa desaparece do sistema
- **Problema:** a origem é debitada em `valor + tarifa`, o destino recebe `valor`, e a tarifa não é creditada em lugar nenhum. A soma dos saldos não fecha; qualquer conciliação pegaria.
- **Correção:** conta interna de receita de tarifas (seed com id fixo), creditada na mesma transação e incluída no `lockInOrder`. Teste de invariante: soma dos saldos antes = soma depois.
- **Mais robusto:** ledger de partidas dobradas (todo lançamento com débito e crédito correspondentes).

### 3. Tarifa e transferência no mesmo lançamento do extrato
- **Problema:** o débito aparece como `-155,15` numa linha só. Bancos mostram "TED enviada -150,00" e "Tarifa TED -5,15" separados.
- **Correção:** a projeção gera um terceiro documento (`transferId:FEE`) com tipo próprio, quando `fee > 0`. O evento já tem `fee`, então só o consumidor muda.

---

## Corretude e concorrência

### 4. Check-then-act na abertura de conta
- **Problema:** `existsByDocument` e depois `save`. Duas aberturas simultâneas com o mesmo CPF: a segunda estoura a `UNIQUE` e vira **500**.
- **Correção:** tratar `DataIntegrityViolationException` e traduzir para `DuplicateAccountException` (409). → [Aula 05](05-postgresql-transacoes-e-concorrencia.md)

### 5. Idempotency-Key reutilizada com outro corpo
- **Problema:** devolve a transferência original em silêncio, mesmo que o cliente mande outro valor ou destino com a mesma chave.
- **Correção:** guardar o hash do corpo e responder 422 se divergir; chave com escopo por cliente e com expiração. → [Aula 08](08-apis-rest.md)

### 6. SSE não funciona com mais de uma réplica
- **Problema:** o `StatementEventBus` é memória local, e as partições do Kafka são divididas entre as réplicas. O cliente conectado na réplica A não recebe eventos consumidos pela B. O manifest K8s define 2 réplicas.
- **Correção:** consumer group próprio por instância para notificações, Redis Pub/Sub, ou Mongo Change Streams. → [Aula 11](11-programacao-reativa-webflux-sse.md)

### 7. OutboxRelay segura a transação durante o envio ao Kafka
- **Problema:** até 10 s por evento com linhas travadas e conexão presa: contradiz a regra de "nada de I/O remoto com transação aberta".
- **Correção:** reservar o lote numa transação curta (`locked_until`), enviar fora de transação, marcar como publicado em outra transação curta. → [Aula 10](10-eventos-outbox-e-kafka.md)

### 8. O consumidor descarta mensagens inválidas sem DLQ
- **Problema:** um evento malformado é logado e perdido.
- **Correção:** publicar num tópico `transfer-events.DLT` com o erro e um header de origem, e ter um processo de reprocessamento.

---

## Escala e operação

### 9. Sem paginação
- **Problema:** `GET /api/accounts` e o extrato devolvem tudo. O export faz `collectList()`, carregando o extrato inteiro em memória, e a Lambda lê o CSV inteiro também.
- **Correção:** paginação por keyset na API; export em streaming (escrever linha a linha num upload multipart); a Lambda lendo o objeto como stream.

### 10. Outbox sem limpeza
- **Problema:** a tabela `outbox_events` cresce para sempre.
- **Correção:** job diário apagando publicados com mais de N dias (em lotes), ou particionamento por data com `DROP PARTITION`.

### 11. O correlation-id não atravessa o Kafka, o Feign nem o serviço reativo
- **Correção:** Micrometer Tracing + OpenTelemetry (propagação automática por HTTP e Kafka). → [Aula 13](13-gateway-observabilidade-e-seguranca.md)

### 12. Sem métricas de negócio nem alertas
- **Correção:** contadores de transferências concluídas e rejeitadas; alerta para eventos pendentes no outbox há mais de 5 minutos, para o circuito aberto e para o atraso (lag) do consumidor Kafka.

---

## Testes

### 13. Nenhum teste contra o Postgres real
- **Problema:** locks, `SKIP LOCKED`, constraints e queries nativas só foram validados manualmente. A ordem dos locks foi testada só com mock.
- **Correção:** Testcontainers + teste de concorrência (exemplo completo na [Aula 12](12-testes.md)).

### 14. Sem teste de contrato do evento
- **Problema:** o produtor pode mudar o JSON do evento e quebrar o statement-service sem nenhum teste falhar.
- **Correção:** Spring Cloud Contract, Pact, ou um schema versionado compartilhado.

### 15. Sem E2E automatizado
- **Correção:** Playwright rodando o roteiro do README contra o docker compose no CI.

---

## Infraestrutura

### 16. EC2 subdimensionada e Terraform incompleto
- **Problema:** `t3.medium` (4 GB) não aguenta a stack. A EC2 fica em sub-rede privada sem NAT nem VPC endpoints criados pelo Terraform: numa conta vazia, ela não baixa o Docker.
- **Correção:** `t4g.large`, módulo de VPC (sub-redes, NAT ou sub-rede pública, endpoint gratuito do S3). → [Aula 16](16-aws-terraform-e-custos.md)

### 17. O CI nunca rodou e as imagens não existem
- **Problema:** `ghcr.io/pocbank/*` é um nome ilustrativo; o workflow nunca foi executado.
- **Correção:** publicar o repositório, ajustar o `images:` do Kustomize para o seu usuário e fazer o CI rodar até o verde. → [Aula 17](17-git-cicd-e-code-review.md)

### 18. Faltam manifests de Kafka, Postgres e Mongo para o Kubernetes
- **Problema:** `kubectl apply -k k8s/overlays/dev` sobe os serviços, mas eles não têm com quem falar.
- **Correção:** overlay com Strimzi, CloudNativePG e o operator do Mongo, ou manifests simples de StatefulSet para dev.

### 19. O LocalStack não guarda estado
- **Problema:** depois de reiniciar, a Lambda precisa ser implantada de novo (`deploy-lambda.sh`), senão os exports não geram relatório.
- **Correção:** executar o deploy da Lambda no script de inicialização (`ready.d`) quando o jar existir.

---

## Qualidade de código

### 20. `useAsync` com `eslint-disable`
- **Problema:** as dependências do hook estão desativadas no lint; não há cancelamento nem cache.
- **Correção:** TanStack Query. → [Aula 14](14-react-e-design-system.md)

### 21. Sem Value Object `Money`
- **Problema:** `BigDecimal` solto, e o arredondamento depende de lembrar de chamar `Amounts.normalize`.
- **Correção:** record `Money` imutável. → [Aula 02](02-modelagem-de-dominio-em-java.md)

### 22. Frontend sem roteador
- **Problema:** as abas são estado local; não há URL por tela (não dá para compartilhar um link nem usar o botão voltar).
- **Correção:** React Router.

---

## Bugs encontrados e corrigidos durante a construção (as lições)

| Bug | Causa | Lição |
|---|---|---|
| Teste do WireMock falhava na primeira execução | Read-timeout de 500 ms; a primeira chamada da JVM é mais lenta | Timeouts em teste precisam de margem grande |
| `X-Correlation-Id` duplicado na resposta | Gateway e serviço colocavam o mesmo header | Proxies em cadeia duplicam headers; `DedupeResponseHeader` |
| `Location: http://account-service:8081/...` | O serviço montava a URL com o host interno | Serviço atrás de proxy precisa de `forward-headers-strategy` |
| Frontend rodaria como root no K8s | Imagem nginx padrão | Pod Security `restricted` exige `nginx-unprivileged` |

Nenhum desses bugs foi pego por teste unitário. Todos apareceram ao **rodar o sistema de verdade**. Testes automatizados não substituem subir o sistema e usá-lo.

---

## Como usar esta lista numa entrevista

Pergunta típica: *"O que você mudaria nesse projeto?"*

Uma boa resposta tem três partes:
1. **Priorize pelo risco:** "Primeiro segurança (item 1), depois a integridade financeira (itens 2 e 4), depois escala (itens 6 e 9)."
2. **Mostre que sabe a correção e o custo:** "Para o SSE com várias réplicas eu usaria Redis Pub/Sub; é mais uma peça, mas o Mongo Change Streams exigiria replica set."
3. **Mostre o que aprendeu:** "Vários bugs só apareceram rodando ponta a ponta, por isso eu adicionaria testes com Testcontainers e um E2E no CI."
