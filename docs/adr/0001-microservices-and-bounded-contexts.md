# ADR 0001: Microsserviços por bounded context

**Status:** aceito

## Contexto
Contas/transferências e extrato têm perfis opostos. Transferência pede consistência forte, baixo volume por conta e escrita crítica. Extrato é leitura intensa, tolera consistência eventual e tem clientes em tempo real.

## Decisão
- **account-service** é o dono das contas e das transferências (modelo transacional, PostgreSQL).
- **statement-service** mantém um read model do extrato (MongoDB), alimentado por eventos.
- **api-gateway** é o ponto único de entrada (roteamento, CORS, correlation-id, retry de GET).
- Cada serviço tem o próprio banco. Nenhum serviço lê a base de outro.

## Consequências
- ✅ Escalam e são implantados de forma independente, e a leitura não disputa lock com a escrita.
- ✅ Falha no extrato não impede transferências.
- ⚠️ O extrato é eventualmente consistente (latência de milissegundos a segundos). A UI mitiga isso com SSE.
- ⚠️ A operação fica mais complexa (tracing, contratos de eventos). Mitigado com correlation-id e envelope de evento versionável.
