# Aula 04: Arquitetura em camadas e hexagonal (Ports & Adapters)

**Objetivo:** entender a organização de pacotes do account-service, a regra de dependência e como garantir essa regra com testes.

---

## 1. A organização

```
br.com.pocbank.account
├── domain/            regras de negócio puras: Account, Transfer, FeePolicy, eventos, exceções
├── application/       casos de uso: TransferService, AccountService, TransferCommand
│   └── port/          interfaces que o caso de uso PRECISA: FraudCheckPort, DomainEventPublisher
├── infrastructure/    detalhes técnicos: Feign, Outbox, Kafka, configuração
│   ├── fraud/         FraudApiClient (Feign) + FraudCheckAdapter (implementa FraudCheckPort)
│   ├── messaging/     OutboxEventPublisher (implementa DomainEventPublisher), OutboxRelay
│   └── config/
└── web/               entrada HTTP: controllers, DTOs, tratamento de erros
```

## 2. A regra de dependência

```
        web  ─────────┐
                      ▼
infrastructure ──▶ application ──▶ domain
                      │
                      └── port (interfaces)  ◀── implementadas pela infrastructure
```
**As setas apontam para dentro.** O domínio não conhece ninguém. A aplicação conhece o domínio e as próprias portas. A infraestrutura e a web conhecem tudo, mas ninguém de dentro as conhece.

### Hexagonal: o nome
A ideia (Alistair Cockburn) é que a aplicação é um núcleo com **portas**:
- **Portas de entrada (driving):** como o mundo aciona a aplicação. Aqui são os casos de uso, chamados pelo controller.
- **Portas de saída (driven):** o que a aplicação precisa do mundo. `FraudCheckPort`, `DomainEventPublisher`.

**Adapters** conectam o mundo às portas: o controller REST, o Feign client, o outbox. Trocar REST por gRPC ou fila, ou trocar o antifraude, é trocar um adapter.

## 3. Por que a porta fica em `application.port` e não em `infrastructure`

Quem define a interface é **quem usa**, não quem implementa. `TransferService` diz "eu preciso de algo que avalie fraude e me devolva uma `FraudDecision`". O Feign só obedece. Se a interface morasse junto do Feign, a aplicação dependeria da infraestrutura, e a seta inverteria.

## 4. O ganho concreto: testes

```java
// TransferServiceTest: nenhum Spring, nenhum banco, nenhuma rede
service = new TransferService(accounts, transfers, fraudCheck,
        new FeePolicyResolver(List.of(new PixFeePolicy(), new TedFeePolicy())), events,
        TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
```
Onze testes de regra de negócio rodam em ~100 ms. Os detalhes de infraestrutura têm testes próprios: WireMock para o Feign, `@WebMvcTest` para o controller.

## 5. Onde a POC foi pragmática (e não purista)

| Ponto | Purista diria | A POC fez | Por quê |
|---|---|---|---|
| Repositórios | Porta `AccountRepositoryPort` no domínio + adapter JPA | `AccountRepository extends JpaRepository` direto no domínio | Spring Data já é uma abstração; duplicar custa caro |
| Entidades | Domínio puro + entidade JPA separada | `@Entity` no domínio | Menos mapeamento ([Aula 02](02-modelagem-de-dominio-em-java.md)) |
| `@Service`/`@Transactional` na aplicação | Sem anotações de framework | Com anotações | O Spring é a plataforma, não um detalhe a ser trocado |

> **Arquitetura é gestão de custo de mudança.** Isole o que tem chance real de mudar ou que atrapalha o teste (fornecedores externos, mensageria). Não gaste abstração com o que não vai mudar (o próprio Spring).

## 6. Alternativas

| Estilo | Ideia | Quando |
|---|---|---|
| **Camadas clássicas** (controller → service → repository) | Simples e conhecido | CRUD, domínio simples |
| **Hexagonal / Clean / Onion** | Domínio no centro, dependências para dentro | Domínio rico, muitas integrações |
| **Vertical slice** | Um pacote por funcionalidade (`transferir/`, `abrirconta/`) com tudo dentro | Muitas features independentes, times por feature |

Dá para combinar: fatias verticais por módulo e hexagonal dentro de cada fatia.

---

## 7. Garantindo a regra com ArchUnit

Regras de arquitetura só em documento acabam violadas. Com ArchUnit elas viram teste:
```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.3.0</version>
    <scope>test</scope>
</dependency>
```
```java
@AnalyzeClasses(packages = "br.com.pocbank.account")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsIndependent = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infrastructure..", "..web..");

    @ArchTest
    static final ArchRule applicationDoesNotKnowInfrastructure = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..web..");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
}
```
Rodou no CI e alguém importou `FraudApiClient` dentro do `TransferService`? O build quebra, com a mensagem apontando a linha.

---

## No seu ERP (Java + PostgreSQL)

### ArchUnit entre módulos
Se o ERP é um monolito com pacotes por módulo, use a convenção de que todo módulo tem um subpacote `api` público. A regra fica: "fora do estoque, só se acessa `..estoque.api..`".
```java
@ArchTest
static final ArchRule acessoAoEstoqueSoPelaApi = classes()
        .that().resideInAPackage("..estoque..")
        .and().resideOutsideOfPackage("..estoque.api..")
        .should().onlyBeAccessed().byClassesThat().resideInAPackage("..estoque..");
```
O **Spring Modulith** faz isso com convenção (cada subpacote direto da aplicação é um módulo) e ainda gera documentação dos módulos. Vale olhar se o ERP usa Spring Boot 3.

### Portas para integrações fiscais e bancárias
```
fiscal/
  application/port/EmissorNotaFiscal.java    ← o caso de uso "emitir nota" depende disto
  infrastructure/sefaz/SefazEmissorAdapter.java
  infrastructure/provedor/ProvedorXEmissorAdapter.java
```
Ganhos: você testa "emitir nota" sem SEFAZ, e troca de provedor de NF-e sem reescrever regra.

### Começo realista
Não reestruture o ERP inteiro. Aplique em **código novo** e em **pontos de dor**: a próxima integração nova já nasce com porta e adapter, e a próxima regra complexa já nasce no domínio com teste unitário.

---

## Exercícios

1. Adicione o ArchUnit ao account-service com as três regras acima e rode. Passa?
2. De propósito, importe `FraudApiClient` no `TransferService` e veja o teste falhar.
3. Suponha que o antifraude passe a ser consumido por fila (pede por Kafka, responde por Kafka). Quais classes mudam? `TransferService` muda? (Pense: a resposta deixa de ser síncrona. Isso muda o **caso de uso**, não só o adapter. Nem toda mudança cabe atrás de uma porta.)
4. No seu ERP, liste as três integrações externas mais críticas e diga se elas estão atrás de uma interface.
