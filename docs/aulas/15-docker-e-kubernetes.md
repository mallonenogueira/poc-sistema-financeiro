# Aula 15: Docker e Kubernetes

**Objetivo:** entender como os serviços viram imagens, como o ambiente local é orquestrado e o que cada objeto do Kubernetes faz, com o motivo de cada configuração.

---

## Parte 1: Docker

### Dockerfile multi-stage
[`docker/java-service.Dockerfile`](../../docker/java-service.Dockerfile):
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build        # estágio 1: tem Maven e JDK (~500 MB)
COPY pom.xml .                                    # 1º só os POMs...
COPY services/account-service/pom.xml services/account-service/
...
COPY ${MODULE}/src ${MODULE}/src                  # ...depois o código
RUN --mount=type=cache,target=/root/.m2 mvn -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine                # estágio 2: só a JRE (~200 MB)
RUN addgroup -S app && adduser -S app -G app
USER app                                          # não roda como root
COPY --from=build /workspace/app.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
```
| Decisão | Por quê |
|---|---|
| **Multi-stage** | A imagem final não leva Maven, JDK nem código-fonte: fica menor e com menos superfície de ataque |
| **POMs antes do código** | O Docker reutiliza camadas em cache. Mudou só código? A camada de dependências não é refeita |
| **`--mount=type=cache`** | O repositório Maven persiste entre builds |
| **Um Dockerfile para todos os serviços** (`ARG MODULE`) | Sem duplicação |
| **JRE, não JDK** | Em runtime não se compila nada |
| **`USER app`** | Se alguém explorar a aplicação, não é root no container |
| **`MaxRAMPercentage=75`** | A JVM calcula o heap pelo limite de memória **do container**, não da máquina. Os 25% restantes são para metaspace, threads e buffers |
| **`ExitOnOutOfMemoryError`** | Com OOM, a JVM morre em vez de ficar num estado zumbi. O orquestrador reinicia um processo limpo |

O front ([`frontend/Dockerfile`](../../frontend/Dockerfile)) segue a mesma ideia: estágio Node para build e `nginx-unprivileged` para servir (porta 8080, sem root, exigido pelo Pod Security `restricted`).

### Docker Compose
[`docker-compose.yml`](../../docker-compose.yml) sobe 10 containers numa rede onde cada um é encontrado pelo **nome do serviço** (`postgres`, `kafka`, `fraud-api`).

Detalhes que evitam dor de cabeça:
```yaml
depends_on:
  postgres: { condition: service_healthy }    # espera o healthcheck passar, não só o container iniciar
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U pocbank -d accounts"]
```
Sem `service_healthy`, o account-service subiria antes de o Postgres aceitar conexões e morreria no boot.

- **Âncora YAML** (`x-java-env: &java-env` + `<<: *java-env`): variáveis comuns declaradas uma vez.
- **Kafka em modo KRaft**, sem Zookeeper. Os dois *listeners* resolvem o problema clássico: `kafka:9092` para os containers e `localhost:29092` para quem roda na máquina.
- **Portas diferentes das padrão** (`5433:5432`) para não conflitar com um Postgres já instalado na máquina (o seu `my_postgres` usa a 5432).

---

## Parte 2: Kubernetes

### O que o Kubernetes resolve
Você declara **o estado desejado** ("quero 2 cópias do account-service com 512 MB cada"), e o Kubernetes **trabalha continuamente para mantê-lo**: reinicia o que cai, redistribui quando uma máquina morre, troca versões aos poucos, escala com a carga.

### Os objetos usados ([`k8s/base`](../../k8s/base))

| Objeto | Função | Arquivo |
|---|---|---|
| **Namespace** | Isolamento lógico + política de segurança | `namespace.yaml` |
| **Deployment** | Mantém N réplicas de um pod e faz rolling update | `*-service.yaml` |
| **Service** | Nome DNS estável + balanceamento entre os pods (que mudam de IP) | idem |
| **ConfigMap** | Configuração não sensível (endereços, região) | `configmap.yaml` |
| **Secret** | Configuração sensível (senha do banco) | overlay dev / ExternalSecret prod |
| **Ingress** | Entrada HTTP externa, roteamento por caminho | `ingress.yaml` |
| **HorizontalPodAutoscaler** | Mais pods quando a CPU passa de 70% | `account-service.yaml` |
| **PodDisruptionBudget** | Garante pelo menos 1 pod durante manutenção de nós | `policies.yaml` |
| **NetworkPolicy** | Firewall entre pods | `policies.yaml` |
| **ServiceAccount** | Identidade do pod (usada para IRSA na AWS) | `statement-service.yaml` |

### Probes: as três perguntas
```yaml
startupProbe:     # "Já terminou de subir?"      Spring + JPA podem levar 30 s
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 5
  failureThreshold: 30          # até 150 s para subir; as outras probes esperam
livenessProbe:    # "Está vivo, ou travou?"      falhou → REINICIA o container
  httpGet: { path: /actuator/health/liveness, port: http }
readinessProbe:   # "Pode receber tráfego agora?" falhou → TIRA do balanceamento (não reinicia)
  httpGet: { path: /actuator/health/readiness, port: http }
```
**Erro clássico:** colocar a checagem do banco na **liveness**. O banco fica lento por 30 s → a liveness de **todos** os pods falha → o Kubernetes reinicia **todos** ao mesmo tempo → os pods voltam todos juntos e martelam o banco. Uma pequena instabilidade vira indisponibilidade total. Dependências externas vão, no máximo, na **readiness**. O Spring Boot, com `probes.enabled`, já separa os dois grupos corretamente.

### Recursos
```yaml
resources:
  requests: { cpu: 250m, memory: 512Mi }   # reservado: usado pelo scheduler para escolher o nó
  limits:   { memory: 768Mi }              # teto: passou disso, o container é morto (OOMKilled)
```
- **Sem limite de CPU, de propósito:** o limite de CPU gera *throttling*, e a JVM (GC e JIT usam várias threads na subida) fica lenta de formas difíceis de diagnosticar. Com `requests` corretos, o pod tem sua fatia garantida e pode usar sobra ociosa.
- **Com limite de memória:** memória não é compressível; sem teto, um pod com vazamento derruba o nó inteiro.
- O `MaxRAMPercentage=75` do Dockerfile conversa com esse limite: heap ≈ 576 MB de 768 MB.

### Deploy sem indisponibilidade
```yaml
strategy:
  rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }   # sobe 1 novo antes de derrubar 1 velho
terminationGracePeriodSeconds: 40
lifecycle:
  preStop: { exec: { command: ["sh", "-c", "sleep 10"] } }
```
e no Spring: `server.shutdown: graceful`.

A sequência ao desligar um pod:
1. O Kubernetes marca o pod para remoção **e, ao mesmo tempo**, começa a tirá-lo dos Services e do Ingress. Isso leva alguns segundos para propagar.
2. O `preStop` espera 10 s. Nesse tempo, o pod ainda **atende** quem chegar pela rota antiga.
3. O Kubernetes envia `SIGTERM`, e o Spring para de aceitar requisições novas e **termina as que estão em andamento**.
4. Se passar de 40 s, `SIGKILL`.

Sem o `preStop`, requisições chegariam num pod que já estava desligando: erros 502 a cada deploy.

### Segurança dos pods
```yaml
securityContext:
  runAsNonRoot: true
  seccompProfile: { type: RuntimeDefault }
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true           # ninguém consegue gravar binário no container
  capabilities: { drop: ["ALL"] }
volumeMounts:
  - { name: tmp, mountPath: /tmp }       # o Tomcat precisa de /tmp gravável
```
Com `pod-security.kubernetes.io/enforce: restricted` no namespace, o Kubernetes **recusa** pods que não cumprem isso.

### NetworkPolicy
Só o `api-gateway` pode falar com os serviços de domínio. Se um pod qualquer for comprometido, ele não alcança o account-service direto. Atenção: só funciona com um plugin de rede que implemente NetworkPolicy (Calico, Cilium, a VPC CNI da AWS com a opção habilitada).

### IRSA: credenciais AWS sem chave
```yaml
kind: ServiceAccount
metadata:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::...:role/pocbank-statement-service
```
O pod recebe credenciais temporárias de uma IAM Role vinculada à ServiceAccount. Não há `AWS_ACCESS_KEY_ID` em lugar nenhum, e o SDK da AWS busca a credencial sozinho.

### Kustomize: base + overlays
```
k8s/
  base/            o que é igual em todo ambiente
  overlays/dev/    1 réplica, senha gerada localmente
  overlays/prod/   endereços da AWS, IRSA real, ExternalSecret, domínio real
```
`kubectl apply -k k8s/overlays/prod` renderiza base + patches. É diferente do **Helm**, que usa templates com variáveis. Kustomize é patch sobre YAML puro (mais simples, já vem no kubectl); Helm é melhor para distribuir pacotes parametrizáveis para terceiros.

### Segredos em produção
`ExternalSecret` (External Secrets Operator) lê do **AWS Secrets Manager** e cria o `Secret` do Kubernetes. A senha nunca passa pelo Git. Alternativas: Sealed Secrets (segredo criptografado no Git), Vault.

### Kubernetes numa máquina só (k3s)
Discutido na conversa: dá para rodar tudo num k3s numa EC2 de 8 GB para demonstração. HPA, probes e rolling update funcionam; PDB e distribuição entre zonas ficam sem efeito, e não há alta disponibilidade.

---

## No seu ERP

Mesmo que o ERP rode em VMs ou num servidor de aplicação tradicional:
- **Conteinerizar** já traz ambiente reproduzível: o "na minha máquina funciona" acaba, e um novo dev sobe tudo com `docker compose up`.
- **Um compose para desenvolvimento** com Postgres na versão de produção, as integrações simuladas por WireMock e o banco com dados de exemplo.
- **Graceful shutdown** vale em qualquer lugar: jobs longos (fechamento, importação) precisam saber que o processo vai parar e terminar ou retomar depois. Olhe como o seu ERP reage a um restart no meio de um processamento.
- **Health checks separados** (vivo × pronto) ajudam qualquer balanceador, com ou sem Kubernetes.
- **Memória da JVM em container:** se o ERP roda em container com `-Xmx` fixo maior que o limite, ele vai ser morto pelo OOM killer sem aviso nos logs da aplicação.

---

## Exercícios

1. `docker history <imagem do account-service>`: identifique as camadas e o tamanho de cada uma.
2. Mude uma linha de código Java e rebuilde. Quais etapas usaram cache?
3. Ative o Kubernetes do Docker Desktop, gere as imagens com os nomes esperados e aplique `k8s/overlays/dev` (faltarão Kafka, Postgres e Mongo no cluster; observe como as probes reagem).
4. Explique, com suas palavras, o que aconteceria num deploy sem o `preStop`.
5. Rode `kubectl kustomize k8s/overlays/prod` e compare o ConfigMap gerado com o da base.
