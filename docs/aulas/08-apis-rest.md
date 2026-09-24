# Aula 08: APIs REST

**Objetivo:** entender as decisões de design da API: recursos, status codes, idempotência, formato de erro, validação e documentação.

---

## 1. Recursos e verbos

| Método | Caminho | Resposta | Observação |
|---|---|---|---|
| `POST` | `/api/accounts` | `201` + `Location` | Cria conta |
| `GET` | `/api/accounts` | `200` | Lista (sem paginação, veja a seção 7) |
| `GET` | `/api/accounts/{id}` | `200` / `404` | |
| `POST` | `/api/transfers` | `201` + `Location` | Exige `Idempotency-Key` |
| `GET` | `/api/transfers/{id}` | `200` / `404` | |
| `GET` | `/api/statements/{conta}` | `200` | Extrato |
| `GET` | `/api/statements/{conta}/stream` | `text/event-stream` | SSE |
| `POST` | `/api/statements/{conta}/exports` | `202 Accepted` | Dispara processamento |

Princípios:
- **Substantivos no caminho, verbo no método HTTP.** `POST /transfers`, e não `POST /executarTransferencia`.
- **`201 Created` + header `Location`** apontando para o recurso criado. O cliente sabe onde buscá-lo depois.
- **`202 Accepted`** no export: o pedido foi aceito, mas o trabalho (a Lambda gerar o relatório) continua depois.

### Métodos seguros e idempotentes
| Método | Seguro (não muda estado) | Idempotente (repetir = mesmo efeito) |
|---|---|---|
| GET | ✔ | ✔ |
| PUT | ✘ | ✔ |
| DELETE | ✘ | ✔ |
| **POST** | ✘ | **✘** |

Por isso o gateway só faz **retry automático em GET** ([`application.yml` do gateway](../../services/api-gateway/src/main/resources/application.yml)). Repetir um POST de transferência pode debitar duas vezes, a menos que ele seja tornado idempotente.

## 2. Idempotency-Key: tornando o POST seguro para repetir

### O problema
O app envia a transferência, o servidor processa, e a **resposta se perde** (timeout, 4G caiu). O app não sabe se deu certo. Se reenviar, pode duplicar. Se não reenviar, pode não ter transferido.

### A solução
O cliente gera uma chave única **por intenção de transferência** e a manda em todas as tentativas:
```
POST /api/transfers
Idempotency-Key: 3f1c...
```
O servidor grava a chave junto com o resultado (`transfers.idempotency_key UNIQUE`). Se a mesma chave chegar de novo, **devolve o resultado original sem reprocessar**.

### Os dois lados da implementação
**Servidor** ([`TransferService`](../../services/account-service/src/main/java/br/com/pocbank/account/application/TransferService.java)): busca pela chave, processa se não achar, e protege a corrida com a constraint única ([Aula 05](05-postgresql-transacoes-e-concorrencia.md)).

**Cliente** ([`TransferForm.tsx`](../../frontend/src/features/transfers/TransferForm.tsx)):
```ts
const idempotencyKey = useRef<string>();
...
idempotencyKey.current ??= crypto.randomUUID();   // gera só se ainda não existe
const transfer = await bankApi.transfer(body, idempotencyKey.current);
idempotencyKey.current = undefined;               // sucesso: a próxima é outra transferência
```
Se a chamada falhar, a chave **fica guardada**, e o clique em "Transferir" de novo reusa a mesma. Há um teste só para isso (`reutiliza a Idempotency-Key ao tentar de novo após falha`).

> Idempotência é um **contrato entre cliente e servidor**. Servidor idempotente com cliente que gera chave nova a cada clique não protege nada.

### Lacunas da implementação da POC (bom assunto de entrevista)
1. **Mesma chave, corpo diferente:** hoje devolve a transferência original em silêncio. O correto é guardar um hash do corpo e responder `422` se a chave for reusada com dados diferentes (é o que a Stripe faz).
2. **Escopo da chave:** a chave deveria ser única **por cliente**, não global. Hoje um cliente poderia "adivinhar" a chave de outro e receber a transferência alheia (combinado com a falta de autenticação, veja a [Aula 13](13-gateway-observabilidade-e-seguranca.md)).
3. **Expiração:** as chaves ficam para sempre. Em geral elas expiram depois de 24 h a alguns dias.
4. **Requisição em andamento:** se a segunda chega enquanto a primeira ainda processa, ela também chama o antifraude e só descobre na constraint. Uma alternativa é gravar a chave com status `PROCESSING` antes e devolver `409` para quem chegar durante o processamento.

## 3. Status codes de erro

[`ApiExceptionHandler`](../../services/account-service/src/main/java/br/com/pocbank/account/web/ApiExceptionHandler.java):

| Situação | Status | Por quê |
|---|---|---|
| JSON inválido, campo faltando, valor negativo | `400 Bad Request` | A requisição está malformada |
| Conta/transferência inexistente | `404 Not Found` | |
| CPF já cadastrado, conflito de concorrência | `409 Conflict` | Conflita com o estado atual |
| Saldo insuficiente, conta bloqueada | `422 Unprocessable Content` | Requisição bem formada, mas a regra de negócio impede |
| Antifraude fora do ar | `503 Service Unavailable` | Temporário: tentar de novo faz sentido |

A distinção **400 × 422** é útil: 400 = "você mandou errado, corrija o código"; 422 = "está certo, mas o negócio não permite agora". 4xx = culpa do cliente; 5xx = culpa do servidor (e só 5xx deveria acordar alguém de madrugada).

### A decisão discutível: transferência recusada devolve 201
A POC responde **201** para uma transferência que o antifraude **recusou**, com `status: "REJECTED"` no corpo. O raciocínio: o recurso *transferência* foi criado e registrado (auditoria), e o resultado de negócio está no corpo. A alternativa seria `422` com o motivo. As duas são defensáveis. O importante é **documentar e ser consistente**.

## 4. Formato de erro: ProblemDetail (RFC 9457)

```json
{
  "type": "https://api.pocbank.dev/problems/InsufficientFunds",
  "title": "InsufficientFunds",
  "status": 422,
  "detail": "Saldo insuficiente na conta 2222...: disponível 1650.00, solicitado 9000.00",
  "instance": "/api/transfers"
}
```
Com `Content-Type: application/problem+json`. É um **padrão da IETF**, suportado nativamente pelo Spring 6 (`ProblemDetail`). O cliente trata todos os erros de todos os serviços do mesmo jeito. O front faz exatamente isso em [`http.ts`](../../frontend/src/api/http.ts).

Como foi feito:
- `spring.mvc.problemdetails.enabled: true` faz o Spring devolver erros de validação e parsing neste formato.
- `ApiExceptionHandler extends ResponseEntityExceptionHandler` + um mapa `exceção → status`. Uma exceção de domínio nova precisa de uma linha no mapa (e, se esquecida, cai no padrão 422).

> Cuidado: o `detail` com saldo é útil aqui, mas em produção exporia dado financeiro a quem chamou. Decida o que vai na mensagem pensando em quem a lê.

## 5. Validação e DTOs

[`TransferDtos`](../../services/account-service/src/main/java/br/com/pocbank/account/web/dto/TransferDtos.java):
```java
public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID targetAccountId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
        @NotNull TransferType type) { }
```
Três camadas de validação, cada uma com um papel:
1. **Bean Validation no DTO:** formato (obrigatório, positivo, 2 casas). Rejeita cedo com 400.
2. **`TransferCommand`:** regras de entrada do caso de uso (origem ≠ destino).
3. **Domínio (`Account.debit`):** invariantes que dependem de estado (saldo).

**Por que DTO separado da entidade?**
- A API não muda quando a tabela muda (e vice-versa).
- Controle do que sai: `AccountResponse` mascara o CPF (`***.456.789-**`), por LGPD.
- Sem risco de *mass assignment* (o cliente mandar `"balance": 1000000` e o JSON ser aplicado direto na entidade).

## 6. Documentação: OpenAPI

`springdoc-openapi` gera a especificação a partir do código. Acesse `http://localhost:8081/swagger-ui.html`. As anotações `@Tag` e `@Operation` enriquecem a documentação.

Duas filosofias:
- **Code-first** (POC): o código gera o contrato. Rápido, e a documentação nunca fica desatualizada.
- **Contract-first**: o YAML OpenAPI é escrito e revisado primeiro, e o código é gerado a partir dele. É melhor quando vários times ou parceiros dependem do contrato antes de ele existir.

## 7. O que ficou faltando

- **Paginação:** `GET /api/accounts` e o extrato devolvem **tudo**. Com um milhão de lançamentos, a resposta explode. Opções:
  - offset/limit (`?page=3&size=50`): simples, mas lento e instável em páginas profundas;
  - **cursor/keyset** (`?after=2026-09-23T20:00:00Z_a188...`): usa o índice, estável com inserções concorrentes. É o certo para extrato.
- **Versionamento:** nenhuma estratégia definida. As opções comuns são no caminho (`/v1/transfers`), em header, ou "nunca quebrar" (só mudanças aditivas). Nesta última, campos novos são opcionais e os clientes ignoram campos desconhecidos.
- **Autenticação/autorização:** nenhuma ([Aula 13](13-gateway-observabilidade-e-seguranca.md)).
- **Rate limiting.**

### O bug do `Location` (e a lição)
No primeiro teste ponta a ponta, o `Location` voltou como `http://account-service:8081/...`, o endereço **interno** do container, inútil para o cliente. O serviço montava a URL a partir da requisição que recebeu (a do gateway). A correção foi `server.forward-headers-strategy: framework`, para respeitar os headers `X-Forwarded-Host/Proto` que o gateway envia. **Todo serviço atrás de proxy precisa disso**, e um teste unitário não pega o problema; só um teste real pega.

---

## No seu ERP (Java + PostgreSQL)

- **Idempotência em operações críticas:** "faturar pedido", "emitir nota", "baixar título", "gerar remessa bancária". Um duplo clique ou um retry de integração não pode gerar duas notas. Chave natural (id do pedido + operação) com constraint única, ou uma `Idempotency-Key` nas APIs de integração.
- **ProblemDetail** para erros de validação fiscal: `type` = código da regra, `detail` = mensagem para o usuário, e campos extras (`problem.setProperty("campos", ...)`) listando os campos com erro. O front passa a exibir qualquer erro de forma padronizada.
- **Paginação por keyset** nas listagens grandes (notas, movimentos):
  ```sql
  SELECT * FROM fiscal.nota
  WHERE (emissao, id) < (:ultimaEmissao, :ultimoId)
  ORDER BY emissao DESC, id DESC
  LIMIT 50;
  ```
- **Nunca serializar entidade JPA** direto nas APIs de integração: o contrato com o parceiro fica refém do schema do banco.
- **400 × 422** nas APIs de integração ajuda o parceiro a saber se o problema é o código dele ou o dado.

---

## Exercícios

1. Com `curl`, envie a mesma transferência duas vezes com a mesma `Idempotency-Key`. Depois envie a mesma chave com **outro valor**. O que volta? Implemente o `422` para esse caso (dica: coluna `request_hash`).
2. Envie `{"amount": "abc"}` e `{"amount": -5}`. Compare os dois ProblemDetails.
3. Adicione paginação por cursor ao extrato (`GET /api/statements/{conta}?before=<occurredAt>&limit=20`).
4. Liste as três operações do seu ERP em que um duplo clique causaria mais estrago. Elas são idempotentes hoje?
