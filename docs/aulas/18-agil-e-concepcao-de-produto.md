# Aula 18: Ágil e concepção colaborativa de produto

**Objetivo:** entender os documentos de processo da POC ([`way-of-working.md`](../agile/way-of-working.md) e [`inception.md`](../product/inception.md)), o porquê de cada prática e como aplicar no dia a dia.

---

## 1. Scrum × Kanban × Scrumban

| | Scrum | Kanban |
|---|---|---|
| Ritmo | Sprints fixas (ex.: 2 semanas) | Fluxo contínuo |
| Compromisso | Meta da sprint | Limite de trabalho em andamento (WIP) |
| Papéis | PO, Scrum Master, Devs | Nenhum obrigatório |
| Mudança no meio | Evitada durante a sprint | A qualquer momento, respeitando o WIP |
| Métrica central | Meta atingida, velocidade | Lead time, cycle time, throughput |
| Bom para | Produto em construção, alinhamento periódico com o negócio | Sustentação, suporte, demanda imprevisível |

A POC propõe **Scrumban**: a cadência do Scrum (planning, review e retro para alinhar com o negócio) mais o board do Kanban com limites de WIP para o dia a dia.

**Para times de ERP** isso costuma encaixar bem: parte do time faz evolução (sprint) e parte atende sustentação e legislação (fluxo contínuo, com a "expedite lane" para urgências como mudança fiscal com prazo).

## 2. As práticas e o problema que cada uma resolve

| Prática | Problema que resolve |
|---|---|
| **Daily olhando o board da direita para a esquerda** | Daily como "status report" individual. Olhar do "quase pronto" para trás foca em **terminar** e destravar |
| **Limite de WIP** | Todo mundo com 5 coisas começadas e nada pronto. Se a coluna de review está cheia, quem está livre vai revisar em vez de puxar mais trabalho |
| **Definition of Ready** | História que entra na sprint sem critério de aceite e gera retrabalho ou bloqueio no meio |
| **Definition of Done** | "Pronto" que não tem teste, não foi revisado ou não está em homologação |
| **Refinamento** | Planning de 4 horas descobrindo o que a história significa |
| **Review com demo real** | Negócio vê o resultado só no fim, quando mudar é caro |
| **Retro com 1 ou 2 ações, com dono e prazo** | Retro que vira desabafo e nada muda |

### Critérios de aceite em Gherkin
```gherkin
Cenário: reenvio da mesma solicitação
  Dado que enviei uma transferência e a conexão caiu
  Quando o app reenvia com a mesma chave de idempotência
  Então não há débito duplicado e recebo o resultado original
```
Escritos a três (PO, dev e QA) no refinamento, eles **viram testes automatizados**. O teste `repeatedIdempotencyKeyReturnsOriginalTransferWithoutReprocessing` é exatamente esse cenário.

## 3. Métricas

**De fluxo:**
- **Lead time:** do pedido à entrega (o que o cliente sente).
- **Cycle time:** do início do trabalho à entrega (o que o time controla).
- **Throughput:** itens entregues por semana.
- **Idade do item em andamento:** o sinal mais cedo de que algo travou.

**DORA** (as quatro métricas que os estudos associam a times de alto desempenho):
| Métrica | Pergunta |
|---|---|
| Frequência de deploy | Com que frequência vamos para produção? |
| Lead time de mudança | Quanto tempo do commit até produção? |
| Taxa de falha de mudança | Quantos deploys causam incidente? |
| Tempo de recuperação (MTTR) | Quanto tempo para restaurar quando quebra? |

> Métrica serve para o time melhorar o processo. Usada para comparar pessoas, ela é manipulada e perde o valor (Lei de Goodhart).

**Estimativas:** story points, dias ideais ou simplesmente **contar itens** (se as histórias forem fatiadas em tamanhos parecidos, o throughput prevê tão bem quanto pontos, com menos discussão). A POC sugere histórias de até 3 dias.

## 4. Concepção colaborativa de produto

"Concepção colaborativa" é negócio, design, engenharia, risco e compliance **desenhando o produto juntos**, antes de escrever código. A engenharia participa da decisão do *que* construir, não só do *como*.

### Lean Inception (Paulo Caroli)
Uma semana de oficinas para alinhar o MVP. Os artefatos usados na POC:

| Artefato | Pergunta que responde |
|---|---|
| **Visão do produto** | Para quem, qual problema, por que somos diferentes? |
| **É / não é / faz / não faz** | Qual é o escopo, e o que **explicitamente** fica de fora? |
| **Personas** | Para quem exatamente? (Marina, que recebe muito PIX; Seu Carlos, que tem medo de golpe) |
| **Jornadas** | Como a persona usa o produto, passo a passo? |
| **Sequenciador / story map** | O que entra em cada onda de entrega? |
| **Hipóteses + métricas** | Como saberemos que funcionou? |

"Não faz" é tão importante quanto "faz": evita que o escopo cresça no meio do caminho.

### Event Storming (Alberto Brandolini)
Oficina com post-its numa parede, com todos os envolvidos:
1. **Eventos de domínio** (laranja), no passado: "Transferência Solicitada", "Fraude Negada", "Conta Debitada".
2. **Colocar em ordem de tempo** e discutir as lacunas.
3. **Comandos** (azul), que disparam eventos, e **políticas** (lilás): "sempre que X, faça Y".
4. **Hotspots** (rosa): dúvidas e conflitos. "E se o antifraude cair?" virou o [ADR 0004](../adr/0004-fraud-check-fail-closed.md).
5. **Agrupar** em contextos: é daí que saíram os dois microsserviços.

A saída não é só entendimento: os **eventos laranja viram literalmente os eventos do Kafka** (`TransferCompleted`, `TransferRejected`), e os **hotspots viram decisões registradas**. É a ponte entre a conversa com o negócio e a arquitetura.

### Hipóteses com métrica
| Hipótese | Métrica | Meta |
|---|---|---|
| Extrato instantâneo reduz contatos no SAC | chamados por 1.000 transferências | −30% |

Sem métrica, não há como saber se a feature valeu o investimento, e o backlog vira uma lista de desejos.

### Como a engenharia contribui
- **Spikes com prazo fixo** para reduzir incerteza antes de estimar ("SSE passa pelo ingress do cliente?").
- **Mostrar trade-offs em linguagem de negócio:** "fail-closed significa que, se o fornecedor cair, ninguém transfere; fail-open significa aceitar risco de fraude nesse período. Qual preferem?".
- **Sugerir o MVP técnico mais barato** que testa a hipótese.
- **Levantar requisitos não funcionais cedo:** volume, latência aceitável, auditoria, LGPD.

---

## No seu ERP

- **Event Storming de um processo real:** "do pedido ao recebimento" (pedido aprovado → estoque reservado → nota autorizada → título gerado → boleto pago → título baixado). Faça com alguém do comercial, do fiscal e do financeiro. Os hotspots que aparecerem são, quase sempre, os bugs e as reclamações que o time já conhece, agora com contexto.
- **"É / não é / faz / não faz"** antes de qualquer módulo novo. ERP sofre muito com escopo que cresce ("já que vamos mexer, dá para incluir...").
- **Gherkin para regras fiscais:** cenários com valores concretos, validados por quem entende da legislação, e depois automatizados com `@ParameterizedTest`.
- **Expedite lane para mudança legal:** mudanças de legislação têm data de vigência. Tratá-las como fluxo separado, com visibilidade, evita a correria de última hora.
- **Medir o lead time de uma correção de bug**, do chamado à produção. É a métrica que o cliente do ERP sente.

---

## Exercícios

1. Escreva o "é / não é / faz / não faz" da próxima funcionalidade do seu time.
2. Faça um Event Storming sozinho (15 minutos, post-its ou papel) do processo mais problemático do ERP. Marque os hotspots.
3. Escreva 3 cenários Gherkin para uma regra de negócio que você mantém e transforme um deles em teste.
4. Meça o cycle time das últimas 10 tarefas do seu time. Qual a média? E o maior? O que aconteceu com ele?
