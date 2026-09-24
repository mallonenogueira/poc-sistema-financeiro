# Aula 14: React e Design System

**Objetivo:** entender a organização do front, os padrões de React usados, a comunicação com a API e como o Design System foi construído (tokens, acessibilidade, fachada substituível).

---

## 1. Organização

```
frontend/src/
├── api/              comunicação com o backend
│   ├── http.ts       fetch + tratamento de ProblemDetail
│   └── bank.ts       funções tipadas por endpoint + SSE
├── hooks/
│   └── useAsync.ts   carregar dados com loading/erro
├── features/         uma pasta por funcionalidade
│   ├── accounts/
│   ├── transfers/    TransferForm + teste
│   ├── statements/
│   └── design-system/  catálogo vivo
├── design-system/    tokens, componentes e fachada
├── App.tsx           navegação por abas
└── main.tsx
```
**Pastas por funcionalidade** (`features/`), não por tipo (`components/`, `services/`, `utils/`). Tudo sobre transferência está junto, e é fácil apagar ou mover uma feature inteira.

## 2. A camada de API

[`http.ts`](../../frontend/src/api/http.ts):
```ts
export async function http<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', ... } });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.status, problem.title ?? response.statusText, problem.detail);
  }
  return ...;
}
```
- **Um ponto só para HTTP:** headers, parsing e erro. Colocar token de autenticação depois é uma linha.
- **ProblemDetail vira `ApiError`**, e as telas tratam erro de forma uniforme ([Aula 08](08-apis-rest.md)).
- **Caminhos relativos (`/api/...`):** em dev, o Vite faz proxy para o gateway; em produção, o nginx. O front não conhece hosts, e não há problema de CORS.

[`bank.ts`](../../frontend/src/api/bank.ts) tem os **tipos** (`Account`, `Transfer`, `StatementEntry`) espelhando os DTOs do backend, e uma função por endpoint. As telas nunca chamam `fetch` diretamente.

> Alternativa em projetos maiores: **gerar** os tipos e o cliente a partir do OpenAPI (`openapi-typescript`, `orval`). Assim o contrato do back e o do front nunca divergem.

## 3. Padrões de React usados

### Hook customizado para dados: `useAsync`
[`useAsync.ts`](../../frontend/src/hooks/useAsync.ts) encapsula `loading`, `data`, `error` e `reload`.

É didático, mas tem problemas: um `eslint-disable` nas dependências, sem cache, sem cancelamento se o componente desmontar, sem deduplicação. **Em projeto real, use TanStack Query (React Query):**
```ts
const { data, isLoading, error, refetch } = useQuery({
  queryKey: ['accounts'],
  queryFn: bankApi.listAccounts,
});
const mutation = useMutation({
  mutationFn: (req) => bankApi.transfer(req, key),
  onSuccess: () => queryClient.invalidateQueries({ queryKey: ['accounts'] }),   // atualiza saldos
});
```
Cache, retry, revalidação, estados de mutação e invalidação vêm prontos.

### Formulário controlado com validação
[`TransferForm.tsx`](../../frontend/src/features/transfers/TransferForm.tsx):
- cada campo é um `useState`, e o valor e o `onChange` ficam no React (componente controlado);
- `validate()` devolve um objeto de erros **antes** de chamar a API, e os erros vão para o `error` de cada campo;
- `noValidate` no `<form>` desliga os balões nativos do navegador, para usar a validação e o visual próprios;
- aceita vírgula decimal (`amount.replace(',', '.')`), porque o usuário brasileiro digita "100,50".

Em formulários grandes (o que o ERP tem muito), `useState` por campo não escala. Use **react-hook-form + zod**: esquema de validação declarativo, tipos derivados do esquema e poucos re-renders.

### `useRef` para o que não é visual
```ts
const idempotencyKey = useRef<string>();
```
A chave de idempotência precisa **sobreviver entre renders** mas **não deve causar re-render** quando muda. É exatamente o papel do `useRef`. Se fosse `useState`, cada mudança redesenharia a tela à toa.

### Efeitos com limpeza: SSE
[`StatementPage.tsx`](../../frontend/src/features/statements/StatementPage.tsx):
```ts
useEffect(() => {
  setLive([]);
  if (!accountId) return;
  return bankApi.subscribeStatement(accountId, (entry) =>
    setLive((current) => [entry, ...current.filter((e) => e.id !== entry.id)]));
}, [accountId]);
```
- `subscribeStatement` devolve a função que fecha o `EventSource`. Retorná-la do `useEffect` faz o React **fechar a conexão** ao trocar de conta ou sair da tela. Sem isso, cada troca de conta deixaria uma conexão aberta (vazamento).
- `setLive(current => ...)` usa a forma funcional porque o callback do SSE captura o estado antigo (*stale closure*).
- Dedupe por `id`: os lançamentos ao vivo e os persistidos são mesclados sem repetir.

### Estados de tela
Toda tela trata **carregando**, **erro**, **vazio** e **sucesso**. É o que mais se esquece e o que mais gera tela em branco em produção.

### O que ficou de fora
Sem roteador (a navegação é por abas no estado; o certo seria **React Router**, para ter URL por tela), sem gerenciador de estado global (não foi necessário) e sem autenticação.

## 4. Design System

### O que é
Um conjunto de **decisões visuais reutilizáveis** (cores, tipografia, espaçamento) mais **componentes** que as aplicam, com regras de uso. O objetivo é consistência entre telas e times, velocidade, e acessibilidade garantida uma vez para todos.

O **Diana** é o design system do cliente da vaga. Como é proprietário, a POC construiu um próprio com a mesma arquitetura ([ADR 0005](../adr/0005-design-system-facade.md)).

### Tokens em duas camadas
[`tokens.css`](../../frontend/src/design-system/tokens.css):
```css
/* Primitivos: a paleta */
--ds-blue-600: #0b5cff;
--ds-red-600: #c62828;

/* Semânticos: a INTENÇÃO */
--ds-color-action: var(--ds-blue-600);
--ds-color-danger: var(--ds-red-600);
```
Os componentes usam **só** os semânticos:
```css
.ds-button--primary { background: var(--ds-color-action); }
```
Consequências:
- Mudou a cor da marca? Troque `--ds-color-action` num lugar só.
- **Dark mode** é redefinir os semânticos dentro de `@media (prefers-color-scheme: dark)`; nenhum componente muda.
- Adotar o Diana = mapear os tokens dele para os nossos nomes semânticos.

### Acessibilidade embutida nos componentes
[`components.tsx`](../../frontend/src/design-system/components.tsx):

| Componente | O que garante | Como |
|---|---|---|
| `TextField`/`Select` | O leitor de tela lê o label e o erro | `useId` → `<label htmlFor>`, `aria-invalid`, `aria-describedby` apontando para a mensagem de erro e a dica |
| `Button` | Botão em loading não recebe clique duplo e é anunciado | `disabled` + `aria-busy` |
| `Alert` | Erro é anunciado na hora; sucesso, educadamente | `role="alert"` × `role="status"` |
| `Tabs` | Navegação semântica | `role="tablist"`/`tab` + `aria-selected` |
| Global | Foco visível; respeito a quem desativa animações | `:focus-visible`, `prefers-reduced-motion` |

Como está no componente, **toda tela herda** acessibilidade sem o dev da feature precisar lembrar. E há teste disso ([`components.test.tsx`](../../frontend/src/design-system/components.test.tsx)).

> `useId` (React 18) gera ids estáveis e únicos, inclusive com renderização no servidor. Antes, era comum `id={Math.random()}`, que quebra o SSR e muda a cada render.

### A fachada
[`design-system/index.ts`](../../frontend/src/design-system/index.ts):
```ts
import './tokens.css';
import './components.css';
export { Alert, Badge, Button, Card, Money, Select, Stack, Tabs, TextField, formatMoney } from './components';
```
As features importam **só daqui**. Trocar a implementação (pelo Diana, MUI ou outro) significa reimplementar este arquivo com a mesma API, e as telas não mudam. É o padrão Facade ([Aula 03](03-solid-e-design-patterns.md)) aplicado ao front.

### Catálogo vivo
A aba "Design System" mostra todos os componentes e variações. Em projetos reais, isso é o **Storybook**: documentação interativa, testes visuais e a referência compartilhada entre design e dev.

### Alternativas de estilo
| Abordagem | Prós | Contras |
|---|---|---|
| **CSS puro + custom properties (POC)** | Zero dependência, tokens nativos | Sem escopo automático de classes |
| CSS Modules | Escopo por arquivo | Tokens continuam em CSS |
| Tailwind | Rápido, tokens no config | HTML verboso, curva de adoção |
| CSS-in-JS (styled-components, Emotion) | Estilo dinâmico por props | Custo em runtime |
| Biblioteca pronta (MUI, Chakra, Ant) | Muitos componentes prontos | Visual genérico, customização trabalhosa |

## 5. React × Angular (a vaga aceita os dois)

| Conceito | React | Angular |
|---|---|---|
| Natureza | Biblioteca de UI | Framework completo |
| Componente | Função + hooks | Classe/`@Component` + template |
| Estado local | `useState` | Propriedades, signals |
| Efeitos | `useEffect` | Lifecycle hooks, `effect()` |
| Chamadas HTTP | `fetch`/TanStack Query | `HttpClient` + RxJS |
| Formulários | react-hook-form | Reactive Forms (nativo) |
| Injeção de dependência | Context | DI nativo |
| Assíncrono | Promises | Observables (RxJS, o mesmo conceito do Reactor) |

Se você entende o Reactor ([Aula 11](11-programacao-reativa-webflux-sse.md)), o RxJS do Angular vai parecer familiar: `Observable` ≈ `Flux`, `switchMap` ≈ `flatMap` com cancelamento.

---

## No seu ERP

- **Design system interno**, mesmo pequeno: tokens + os 10 componentes mais usados (campo, select, botão, tabela, modal, alerta). Telas de ERP são centenas de formulários parecidos; a consistência e a velocidade aparecem rápido.
- **Componentes de domínio sobre o DS:** `CampoCnpj` (máscara + validação do dígito), `CampoMoeda` (vírgula, milhar, 2 casas), `SeletorProduto` (busca com debounce). Escritos uma vez e usados em todas as telas.
- **Tabelas grandes:** TanStack Table + virtualização (renderizar só as linhas visíveis) para listas de milhares de itens.
- **Formulários:** react-hook-form + zod, com os mesmos limites do backend (idealmente gerados do OpenAPI).
- **Idempotência no cliente:** botões de "Faturar", "Emitir nota" e "Baixar" com `loading` + chave reutilizada em caso de erro, exatamente como o `TransferForm`.

---

## Exercícios

1. Rode `npm run dev` no `frontend` (com o compose no ar) e abra o DevTools → Network. Faça uma transferência e ache o header `Idempotency-Key`. Simule falha (desligue o account-service) e confira se a chave se repete na nova tentativa.
2. Mude `--ds-color-action` para verde. Quantos arquivos você editou?
3. Use o leitor de tela do sistema (Narrador no Windows) no formulário de transferência com um campo em erro. O que ele lê?
4. Reescreva `AccountsPage` com TanStack Query.
5. No ERP, liste os 5 campos de formulário mais repetidos. Eles têm um componente único ou cada tela reimplementa?
