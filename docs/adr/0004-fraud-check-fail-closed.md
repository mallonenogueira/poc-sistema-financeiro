# ADR 0004: Antifraude síncrono, fail-closed e fora da transação

**Status:** aceito

## Contexto
Toda transferência precisa de análise antifraude de um fornecedor externo (HTTP). O fornecedor pode ficar lento ou indisponível.

## Decisão
1. **Chamada síncrona antes de efetivar**, porque o dinheiro não pode sair antes da análise.
2. **Fora da transação de banco**: a chamada remota acontece antes de abrir a transação ([`TransferService`](../../services/account-service/src/main/java/br/com/pocbank/account/application/TransferService.java)), para não segurar locks e conexões do pool durante I/O de rede.
3. **Resiliência**: OpenFeign com connect/read timeout, **Time Limiter** (3s) e **Circuit Breaker** (Spring Cloud CircuitBreaker + Resilience4j). Com o circuito aberto, a falha é imediata e o fornecedor não recebe mais carga.
4. **Fail-closed**: sem resposta do antifraude, a transferência **não** acontece (`503`, com mensagem para tentar de novo). Em finanças, o custo de um falso negativo (fraude aprovada) supera o de indisponibilidade momentânea.
5. **Rejeições são persistidas** (`REJECTED` + motivo) e publicadas como evento, para trilha de auditoria.
6. **ACL**: o contrato externo (`APPROVED/DENIED`) é traduzido para o modelo do domínio (`FraudDecision`) no adapter.

## Consequências
- ✅ Nenhum lock é mantido durante chamadas de rede, e há proteção contra falhas em cascata.
- ✅ O comportamento é coberto por testes com **WireMock** (aprovação, negação, 500, timeout).
- ⚠️ A disponibilidade das transferências depende do antifraude. Uma evolução possível é uma política de fallback por faixa de valor (ex.: aprovar abaixo de X com análise posterior), decidida com o negócio e o compliance.
