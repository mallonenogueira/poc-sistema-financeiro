# Concepção colaborativa: Transferências e Extrato em tempo real

Registro de uma concepção no formato **Lean Inception** + **Event Storming**, feita com negócio, design, engenharia, risco/antifraude e compliance na mesma sala. O código da POC é o resultado dessas decisões.

## 1. Visão do produto
> **Para** clientes pessoa física **que** precisam movimentar dinheiro com segurança,
> **o** PocBank Transferências **é um** fluxo de transferência com extrato instantâneo
> **que** dá confiança imediata de que o dinheiro chegou.
> **Diferente de** extratos que atualizam em lote, **nosso produto** mostra o lançamento em segundos e nunca duplica uma cobrança.

## 2. É / não é / faz / não faz
| É | Não é |
|---|---|
| Transferência entre contas com antifraude | Um app de investimentos |
| Extrato em tempo real | Um relatório contábil |

| Faz | Não faz (neste MVP) |
|---|---|
| PIX e TED com tarifa por modalidade | Agendamento e recorrência |
| Recusa com motivo claro | Contestação de transação |
| Exportação do extrato | Integração com o SPI real do BACEN |

## 3. Personas
- **Marina, 29, autônoma**: recebe muitos PIX por dia e quer ver cada entrada na hora.
- **Seu Carlos, 64**: faz poucas TEDs de valor alto, tem medo de golpe e quer saber o motivo quando algo é bloqueado.
- **Analista de fraude**: precisa de trilha de auditoria de toda recusa.

## 4. Event Storming (big picture)
```
[Conta Aberta] → [Transferência Solicitada] → (política: analisar fraude)
      → [Fraude Aprovada] → [Conta Debitada] + [Conta Creditada] → [Transferência Concluída]
      → [Fraude Negada]   → [Transferência Rejeitada]
[Transferência Concluída] → (política: atualizar extrato) → [Lançamento Registrado] → (notificar cliente)
[Extrato Exportado] → (política: gerar resumo) → [Resumo Gerado]
```
**Hotspots discutidos:**
- *E se o antifraude cair?* Compliance decidiu **fail-closed** ([ADR 0004](../adr/0004-fraud-check-fail-closed.md)).
- *E se o app reenviar a transferência?* A solução é a **Idempotency-Key** obrigatória.
- *Extrato atrasado gera ligação no SAC?* A resposta foi usar SSE, e a meta é p95 < 2s entre a conclusão e a exibição.

**Bounded contexts identificados:** Contas & Transferências e Extrato, que viraram os dois microsserviços ([ADR 0001](../adr/0001-microservices-and-bounded-contexts.md)).

## 5. Story map e sequenciamento
| Atividade → | Abrir conta | Transferir | Acompanhar | Exportar |
|---|---|---|---|---|
| **MVP (onda 1)** | abrir com CPF | PIX com antifraude | extrato listado | – |
| **Onda 2** | – | TED com tarifa, idempotência | extrato em tempo real | CSV |
| **Onda 3** | bloqueio de conta | limites por horário | notificação push | resumo automático (Lambda) |

## 6. Hipóteses e métricas de sucesso
| Hipótese | Métrica | Meta |
|---|---|---|
| Extrato instantâneo reduz contatos no SAC sobre "o dinheiro caiu?" | chamados/1000 transferências | −30% |
| Mostrar o motivo da recusa reduz retentativas frustradas | retentativas após recusa | −50% |
| Idempotência elimina débito duplicado | débitos duplicados | 0 |

## 7. Como a engenharia participa
- **Discovery**: spikes técnicos com prazo fixo (ex.: validar SSE através do ingress) antes de estimar.
- **Protótipo navegável** montado com o Design System, para testar com usuários antes de codar o backend.
- **Critérios de aceite** escritos a quatro mãos (PO + dev + QA), e viram testes automatizados.
- **Trade-offs visíveis**: toda decisão com impacto de negócio vira ADR com consequências explícitas.
