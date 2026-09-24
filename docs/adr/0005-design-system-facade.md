# ADR 0005: Design System como fachada substituível

**Status:** aceito

## Contexto
O cliente usa o Design System **Diana**, que é proprietário e não está disponível publicamente. A POC precisa mostrar o mesmo modo de trabalho (tokens, componentes, consistência, acessibilidade) sem depender do pacote real.

## Decisão
- **Design tokens** em CSS custom properties, em duas camadas: *primitivos* (`--ds-blue-600`) e *semânticos* (`--ds-color-action`). Componentes só usam os semânticos.
- Componentes acessíveis por padrão: `label` associado, `aria-invalid`/`aria-describedby` em campos, `role=alert|status` em alertas, `aria-busy` em loading, `role=tablist` em abas, foco visível, `prefers-reduced-motion` e dark mode.
- **Fachada única** ([`src/design-system/index.ts`](../../frontend/src/design-system/index.ts)): as telas importam só dali. Para adotar o Diana basta reimplementar a fachada (ou um adapter de props) mantendo a API.
- A aba *Design System* do app funciona como catálogo vivo (papel do Storybook).

## Consequências
- ✅ Migrar para o DS corporativo fica restrito a uma pasta.
- ✅ As regras de acessibilidade ficam garantidas por testes de componente.
- ⚠️ Num projeto real, nada de criar componentes paralelos ao Diana. Lacunas viram contribuição ou pedido ao time do DS.
