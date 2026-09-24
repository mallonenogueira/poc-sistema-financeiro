# Guia de Code Review

Objetivo: **compartilhar conhecimento e reduzir risco**, não provar quem está certo. Revisamos o código, nunca a pessoa.

## Acordos
- PRs pequenos (idealmente < 400 linhas alteradas). PR grande é dividido ou revisado em par.
- Primeira resposta em até 4h úteis. PR parado vira item da daily.
- Pelo menos 1 aprovação, e 2 quando a mudança toca dinheiro, segurança ou contrato público (CODEOWNERS).
- CI verde é pré-requisito. O revisor não gasta tempo com o que o linter pega.

## Prefixos nos comentários
| Prefixo | Significado |
|---|---|
| `blocker:` | Precisa mudar antes do merge (bug, risco de segurança, perda de dado) |
| `sugestão:` | Melhoria recomendada; o autor decide |
| `nit:` | Detalhe cosmético, não bloqueia |
| `pergunta:` | Quero entender; não é pedido de mudança |
| `elogio:` | Algo bem feito que vale destacar |

## Checklist do revisor

**Correção e domínio**
- [ ] A regra de negócio está no domínio e não no controller ou repository?
- [ ] Dinheiro em `BigDecimal` com escala e arredondamento explícitos?
- [ ] Concorrência: dá para ter dupla execução ou race? Locks em ordem? Idempotência?

**Sistemas distribuídos**
- [ ] O evento é publicado via outbox (sem dual write)?
- [ ] O consumidor é idempotente? O que acontece com mensagem duplicada, fora de ordem ou malformada?
- [ ] Chamadas remotas têm timeout, circuit breaker e política de falha clara?
- [ ] Mudança de contrato (API ou evento) é retrocompatível?

**Dados**
- [ ] A migration roda com a versão anterior da aplicação no ar (expand/contract)?
- [ ] Índices para as novas consultas? Sem N+1?

**Segurança e compliance**
- [ ] Nenhum dado pessoal ou financeiro em log? Entrada validada?
- [ ] Segredos fora do código? IAM de menor privilégio?

**Testes e manutenção**
- [ ] Os testes cobrem caminho feliz, erros e bordas? Falhariam se o código quebrasse?
- [ ] Integração externa testada com WireMock (contrato, timeout, erro)?
- [ ] Nomes claros e funções pequenas? O comentário explica o *porquê*?

**Frontend**
- [ ] Usa componentes do Design System (sem CSS ad-hoc que duplica token)?
- [ ] Acessível: label, foco, leitor de tela, contraste?
- [ ] Estados de loading, erro e vazio tratados?
