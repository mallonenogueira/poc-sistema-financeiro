## Contexto
<!-- Qual problema este PR resolve? Link da história/tarefa no board. -->

## O que mudou
-

## Como testar
<!-- Passos, payloads de exemplo, prints. -->

## Checklist do autor
- [ ] Testes automatizados cobrem o comportamento novo (unitário e, se houver integração externa, WireMock)
- [ ] Contratos de API/eventos: mudança é retrocompatível (ou versionada) — consumidores avisados
- [ ] Sem dados sensíveis em logs (CPF, saldo, tokens)
- [ ] Migrations são reversíveis/compatíveis com a versão anterior rodando (deploy sem downtime)
- [ ] Documentação/ADR atualizada quando há decisão arquitetural
- [ ] Componentes visuais usam o Design System (sem estilos ad-hoc)

Guia de revisão: [docs/engineering/code-review.md](../docs/engineering/code-review.md)
