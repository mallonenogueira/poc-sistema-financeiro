# Modo de trabalho do squad (Scrum + Kanban)

Usamos **Scrum** como cadência de planejamento e alinhamento com o negócio, e **Kanban** para gerenciar o fluxo diário. Na prática, é "Scrumban".

## Cadência (sprint de 2 semanas)
| Rito | Duração | Objetivo |
|---|---|---|
| Planning | 2h | Meta da sprint + seleção de itens prontos (DoR) |
| Daily | 15 min | Olhar o **board da direita para a esquerda**: o que está travado e o que fecha hoje |
| Refinamento | 1h/semana | Quebrar histórias, critérios de aceite, riscos técnicos, spikes |
| Review | 1h | Demo do incremento em ambiente real para stakeholders |
| Retro | 1h | Uma ou duas ações de melhoria com dono e prazo |

## Board Kanban
`Backlog pronto` → `Em desenvolvimento (WIP 3)` → `Code review (WIP 3)` → `Teste/QA (WIP 2)` → `Pronto para deploy` → `Em produção`

- **WIP limits** forçam terminar antes de começar. Se a coluna de review está cheia, quem está livre vai revisar.
- **Expedite lane** para incidentes de produção.
- Itens bloqueados ganham marcação e causa, que vira insumo para a retro.

## Definition of Ready (DoR)
- [ ] História no formato *Como… quero… para…*, com valor de negócio claro
- [ ] Critérios de aceite em Gherkin (Dado/Quando/Então)
- [ ] Dependências (outros times, fornecedores, compliance) mapeadas
- [ ] Layout validado com o Design System (quando há UI)
- [ ] Cabe em até 3 dias; se não, é quebrada

## Definition of Done (DoD)
- [ ] Código revisado e aprovado (guia de code review)
- [ ] Testes automatizados passando no CI (unitário + integração)
- [ ] Sem regressão de cobertura nas classes de domínio
- [ ] Observabilidade: logs com correlation-id, métricas e alertas para o fluxo novo
- [ ] Documentação atualizada (OpenAPI, ADR se houve decisão)
- [ ] Implantado em homologação e validado pelo PO
- [ ] Feature flag, quando o lançamento é gradual

## Métricas acompanhadas
- **Fluxo**: lead time, cycle time, throughput, idade dos itens em andamento.
- **DORA**: frequência de deploy, lead time de mudança, taxa de falha, MTTR.
- Métricas servem para o time melhorar o processo, nunca para comparar pessoas.

## Exemplo de história (desta POC)
> **Como** cliente, **quero** transferir via PIX ou TED **para** pagar pessoas de qualquer banco.
>
> **Cenário: transferência PIX aprovada**
> Dado que tenho saldo de R$ 1.000
> Quando transfiro R$ 100 via PIX
> Então meu saldo passa a R$ 900 e o lançamento aparece no extrato em até 2 segundos
>
> **Cenário: reenvio da mesma solicitação**
> Dado que enviei uma transferência e a conexão caiu
> Quando o app reenvia com a mesma chave de idempotência
> Então não há débito duplicado e recebo o resultado original
