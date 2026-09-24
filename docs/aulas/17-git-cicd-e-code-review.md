# Aula 17: Git, CI/CD e code review

**Objetivo:** entender o pipeline, as práticas de repositório e o processo de revisão, e o que dá para implantar no seu time sem grandes mudanças.

> Aviso honesto: o pipeline **nunca rodou** no GitHub (a pasta nem é um repositório git ainda), e as imagens `ghcr.io/pocbank/...` não existem. O YAML está completo e segue a sintaxe atual, mas não foi validado em execução.

---

## 1. O pipeline

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml):
```
PR ou push ─┬─▶ backend   (mvn verify: compila, testa, cobertura)
            ├─▶ frontend  (lint, testes, build)
            └─▶ iac       (terraform fmt/validate, kustomize build)
                     │
      só em push na main
                     ▼
               images  (matrix: 4 imagens → GHCR, tag = SHA do commit)
                     ▼
               deploy-dev  (OIDC na AWS → kubectl apply → rollout status → Lambda)
```

### Decisões do workflow
| Decisão | Por quê |
|---|---|
| Jobs **paralelos** (back, front, IaC) | Feedback mais rápido no PR |
| `concurrency` com `cancel-in-progress` | Novo push no PR cancela a execução antiga: economiza minutos |
| `permissions: contents: read` no topo | Menor privilégio para o token do GitHub; cada job pede só o que precisa (`packages: write`, `id-token: write`) |
| Cache do Maven, do npm e das camadas Docker (`type=gha`) | Builds de minutos, não dezenas de minutos |
| Relatórios de teste como artifact (`if: always()`) | Mesmo quando falha, dá para ver o que falhou |
| **Tag da imagem = SHA do commit** | Rastreável e imutável. `latest` não diz o que está rodando |
| **Matrix** para as imagens | Um bloco de YAML para 4 imagens |
| **OIDC** (`id-token: write` + `role-to-assume`) | O GitHub troca um token de identidade por credenciais AWS temporárias. **Nenhuma access key salva no repositório** |
| `environment: dev` | Regras de aprovação e segredos por ambiente ficam configurados no GitHub |
| `kubectl rollout status` | O deploy só é "verde" se os pods novos ficarem prontos. Senão, o job falha |

### CI × CD × CD
- **Integração contínua (CI):** todo commit é compilado e testado automaticamente. Integração frequente em branch curta.
- **Entrega contínua (Continuous Delivery):** todo commit na main **pode** ir para produção com um clique.
- **Implantação contínua (Continuous Deployment):** todo commit na main **vai** para produção sozinho.

A POC faz CI + deploy automático em dev. Produção teria um passo com aprovação.

### O que um pipeline de banco teria a mais
- **Análise estática:** SonarQube/SonarCloud (bugs, code smells, cobertura mínima como *quality gate*).
- **Segurança:** análise de dependências vulneráveis (OWASP Dependency-Check, Snyk, Dependabot alerts), scan da imagem (Trivy), busca de segredos no código (gitleaks), SAST.
- **Testes de contrato e de integração** com Testcontainers.
- **Deploy progressivo:** canary (5% do tráfego para a versão nova, observa métricas, depois 100%) ou blue/green, com rollback automático se a taxa de erro subir.
- **Migrations separadas do deploy** ou validadas contra uma cópia do banco.

## 2. Estratégia de branches

| Modelo | Como | Quando |
|---|---|---|
| **Trunk-based** | Branches curtas (horas a 1-2 dias), merge frequente na main, feature flags para o que não está pronto | Times com CI forte, deploy frequente. É o que os estudos DORA associam a alto desempenho |
| **GitHub Flow** | Branch por feature → PR → main → deploy | Simples, bom para a maioria |
| **GitFlow** | `develop`, `release/*`, `hotfix/*`, `main` | Releases versionadas e espaçadas (software instalado no cliente) |

**Para ERP:** se o ERP é instalado em clientes com versões diferentes, alguma variação de GitFlow (branches de release para manutenção) costuma ser necessária. Se é SaaS com uma versão para todos, trunk-based ou GitHub Flow.

### Commits que contam uma história
```
feat(transfer): cobra tarifa em lançamento separado no extrato
fix(account): devolve 409 em abertura concorrente com mesmo CPF
refactor(outbox): libera a transação antes de publicar no Kafka
```
*Conventional Commits*: tipo(escopo): descrição. Facilita revisar o histórico e gerar changelog. Um commit = uma mudança lógica; "wip", "ajustes" e "fix2" não ajudam ninguém daqui a seis meses.

## 3. Governança do repositório

| Arquivo | Função |
|---|---|
| [`pull_request_template.md`](../../.github/pull_request_template.md) | Todo PR nasce com contexto, como testar e um checklist (testes, contrato, dados sensíveis em log, migration compatível) |
| [`CODEOWNERS`](../../.github/CODEOWNERS) | Revisor obrigatório por pasta. Mudou o design system? O time do DS é chamado automaticamente |
| [`dependabot.yml`](../../.github/dependabot.yml) | PRs automáticos de atualização de dependências (Maven, npm, Actions, imagens Docker), com o Spring agrupado em um PR |
| [`.editorconfig`](../../.editorconfig) | Indentação e fim de linha iguais em qualquer editor |
| `.gitignore` | Nunca versionar `target/`, `node_modules/`, `*.tfstate`, `terraform.tfvars` |

**Proteção de branch** (configurada no GitHub, não em arquivo): main só recebe PR, com CI verde e aprovação dos CODEOWNERS, sem push direto e sem force push.

## 4. Code review

O guia completo está em [`docs/engineering/code-review.md`](../engineering/code-review.md). Os pontos centrais:

### Para que serve
1. **Compartilhar conhecimento.** Pelo menos duas pessoas entendem cada mudança.
2. **Pegar o que o CI não pega:** regra de negócio errada, concorrência, segurança, design.
3. **Manter a coerência** do código.

Não serve para mostrar quem sabe mais nem para discutir estilo que um formatador resolveria.

### Prefixos nos comentários
`blocker:` precisa mudar · `sugestão:` o autor decide · `nit:` detalhe cosmético · `pergunta:` quero entender · `elogio:` reforço positivo.

Eles tiram a ambiguidade: "por que não usou X?" é uma crítica ou uma dúvida? Com o prefixo, o autor sabe o que fazer.

### Como escrever bons comentários
```
✘ "Isso está errado."
✔ "blocker: se duas requisições chegarem juntas com o mesmo CPF, as duas passam no
   existsByDocument e a segunda estoura a UNIQUE como 500. Sugiro tratar a
   DataIntegrityViolationException e devolver 409 (como no TransferService)."
```
Explique o **problema** (cenário concreto), o **impacto** e uma **sugestão**. Comente o código, não a pessoa.

### O checklist de um revisor sênior (resumo)
- Regra de negócio no domínio? Dinheiro em BigDecimal?
- Concorrência: dupla execução, race, ordem de locks, idempotência?
- Distribuído: dual write? consumidor idempotente? timeout em chamada remota?
- Migration compatível com a versão anterior no ar?
- Dado sensível em log? Autorização por objeto?
- Os testes falhariam se o código quebrasse?

### PR pequeno
Um PR de 2.000 linhas recebe "LGTM"; um de 200 recebe revisão de verdade. Divida em PRs que façam sentido sozinhos (primeiro a migration e o domínio, depois o endpoint, depois a tela), com feature flag se preciso.

## 5. ADRs: registrando decisões

[`docs/adr/`](../adr/). Formato curto: **Contexto → Decisão → Consequências** (inclusive as negativas).

Por quê: daqui a um ano alguém vai perguntar "por que o extrato está no Mongo?". Sem registro, a resposta é "não sei, já estava assim", e a decisão ou é mantida por medo ou é revertida sem conhecer os motivos. Um ADR é imutável; se a decisão mudar, cria-se um novo ADR que substitui o anterior.

---

## No seu ERP

Ordem sugerida, do mais barato para o mais caro:
1. **CI em todo PR** rodando build + testes (mesmo que sejam poucos). Branch protegida exigindo CI verde.
2. **PR template + prefixos de comentário.** Custo zero, efeito imediato na qualidade das revisões.
3. **ADRs** para as próximas decisões relevantes (uma página cada).
4. **Dependabot** ou equivalente: bibliotecas desatualizadas são a porta de entrada mais comum de vulnerabilidades.
5. **Validação das migrations no CI:** subir um Postgres (Testcontainers ou serviço do workflow) e rodar o Flyway do zero a cada PR.
6. **Artefato imutável:** o mesmo build testado é o que vai para homologação e produção (não recompilar por ambiente).

---

## Exercícios

1. Transforme a pasta da POC num repositório (`git init`), faça o primeiro commit e suba para um repositório seu no GitHub. O CI roda? O que falha e por quê?
2. Configure a proteção da branch main exigindo os jobs `backend` e `frontend`.
3. Abra um PR com uma mudança pequena (ex.: a correção do 409 da abertura de conta) e revise o seu próprio PR usando o checklist.
4. Escreva o ADR 0006: "Spring Cloud Gateway × Ingress × gateway corporativo".
5. No ERP: quanto tempo leva hoje do merge até produção? Quais etapas são manuais?
