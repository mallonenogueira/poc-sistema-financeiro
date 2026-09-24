# Aula 16: AWS (S3, Lambda, EC2), Terraform e custos

**Objetivo:** entender cada serviço AWS usado, por que foi usado, como a infraestrutura é descrita em código e quanto isso custa.

---

## 1. O fluxo AWS da POC

```
Extrato → "Exportar CSV"
  statement-service ──PutObject──▶ S3: exports/{conta}/{timestamp}.csv
                                         │ evento s3:ObjectCreated (filtro exports/*.csv)
                                         ▼
                                  Lambda statement-report (Java 21)
                                    lê o CSV, soma créditos, débitos e tarifas
                                         │
                                         ▼
                                  S3: reports/{conta}/{timestamp}.json
```
Em dev, tudo roda no **LocalStack** (emulador de AWS em container). O Terraform descreve a versão real.

Para ver os relatórios localmente: `http://localhost:4566/pocbank-statements/reports/<conta>/<timestamp>.json`, ou `docker compose exec localstack awslocal s3 ls s3://pocbank-statements --recursive`. Após reiniciar o LocalStack, rode de novo o `deploy-lambda.sh`.

## 2. S3 (Simple Storage Service)

Armazenamento de **objetos**: arquivos identificados por `bucket` + `chave`. Não é um sistema de arquivos: `exports/` não é uma pasta, é só um **prefixo** da chave.

Uso na POC ([`StatementExportService`](../../services/statement-service/src/main/java/br/com/pocbank/statement/application/StatementExportService.java)), com o SDK v2 assíncrono (casa com o WebFlux):
```java
Mono.fromFuture(() -> s3.putObject(request, AsyncRequestBody.fromString(csv)))
```
Configuração no Terraform ([`s3.tf`](../../infra/terraform/s3.tf)):

| Recurso | Para quê |
|---|---|
| `public_access_block` | Impede qualquer acesso público, mesmo por engano. Buckets públicos são uma das maiores fontes de vazamento de dados |
| Criptografia `aws:kms` | Dados cifrados em repouso |
| Versionamento | Sobrescrever ou apagar não perde a versão anterior |
| Lifecycle: exports expiram em 30 dias | LGPD: não guardar dado pessoal além do necessário |
| Lifecycle: reports vão para Glacier IR em 90 dias | Armazenamento mais barato para o que quase não é lido |

## 3. Lambda

Função que roda **sob demanda**, disparada por eventos, sem servidor para gerenciar. Cobra por invocação e por tempo × memória.

[`StatementReportHandler`](../../lambda/statement-report-lambda/src/main/java/br/com/pocbank/lambda/StatementReportHandler.java):
```java
public class StatementReportHandler implements RequestHandler<S3Event, List<String>> {

    private final S3Client s3;      // criado UMA vez por container, fora do handler

    public StatementReportHandler() {
        this(S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()) ... .build());
    }

    @Override
    public List<String> handleRequest(S3Event event, Context context) {
        return event.getRecords().stream()
                .map(r -> process(r.getS3().getBucket().getName(),
                        URLDecoder.decode(r.getS3().getObject().getKey(), StandardCharsets.UTF_8)))
                .toList();
    }
}
```
Cada detalhe tem motivo:

| Detalhe | Por quê |
|---|---|
| Cliente S3 no construtor | A Lambda reaproveita o container entre invocações "quentes". Criar o cliente a cada chamada desperdiça tempo |
| `UrlConnectionHttpClient` (e exclusão do Netty e do Apache no `pom.xml`) | Jar menor, cold start menor |
| `URLDecoder.decode` na chave | As chaves chegam **URL-encoded** no evento (espaço vira `+`). Esquecer isso gera `NoSuchKey` só para alguns arquivos: um bug clássico |
| `if (!key.startsWith("exports/"))` | Defesa contra **loop infinito**: se o gatilho fosse mal configurado para o bucket todo, a Lambda gravaria em `reports/`, o que dispararia a Lambda de novo... e a conta da AWS explodiria |
| Jar "shaded" (`maven-shade-plugin`) | A Lambda Java recebe um único jar com todas as dependências |

### Cold start (o ponto fraco do Java em Lambda)
A primeira invocação precisa iniciar a JVM e carregar as classes: de 1 a vários segundos. Mitigações:
- **SnapStart** (configurado no [`lambda.tf`](../../infra/terraform/lambda.tf)): a AWS tira um snapshot da função já inicializada e restaura em vez de iniciar do zero;
- dependências enxutas;
- mais memória (a CPU é proporcional à memória);
- GraalVM native image (inicia em milissegundos, mas o build é complexo);
- *provisioned concurrency* (instâncias sempre quentes, pagas).

Para um relatório assíncrono, o cold start **não importa**: ninguém está esperando. É um dos motivos de a Lambda caber bem aqui.

### Por que Lambda e não mais um endpoint no statement-service?
O trabalho é **esporádico, assíncrono e independente**. A Lambda só custa quando roda, escala sozinha e não disputa recursos com o serviço que serve o extrato. Sendo honesto: o motivo principal foi a vaga pedir Lambda, e o cálculo em si é simples. O mesmo formato serviria para algo pesado de verdade (gerar o PDF do extrato, o informe de rendimentos).

### Limites a conhecer
15 minutos por execução; payload de invocação de 6 MB; `/tmp` limitado (configurável); concorrência limitada por conta. Não serve para processos longos ou com estado.

## 4. EC2

Máquina virtual. Na POC, é só um host de demonstração no Terraform ([`ec2.tf`](../../infra/terraform/ec2.tf)):

| Configuração | Por quê |
|---|---|
| AMI via parâmetro SSM | Sempre a imagem Amazon Linux mais recente, sem ID fixo no código |
| `http_tokens = "required"` (**IMDSv2**) | Protege contra ataques SSRF que roubam credenciais pelo endpoint de metadados |
| **SSM Session Manager** em vez de SSH | Sem porta 22 aberta, sem chave `.pem` para gerenciar, e com acesso auditado |
| Disco criptografado | Padrão de segurança |
| `user_data` | Script que roda no primeiro boot (instala Docker) |

**Erros admitidos:**
- `t3.medium` (4 GB) **não aguenta** a stack inteira; o mínimo realista é 8 GB (`t3.large`/`t4g.large`);
- a EC2 fica em sub-rede privada, mas o Terraform não cria VPC, NAT Gateway nem VPC endpoints. Numa conta vazia, ela não conseguiria baixar o Docker no boot.

## 5. IAM: menor privilégio

[`lambda.tf`](../../infra/terraform/lambda.tf):
```hcl
statement {
  actions   = ["s3:GetObject"]
  resources = ["${aws_s3_bucket.statements.arn}/exports/*"]    # só lê exports
}
statement {
  actions   = ["s3:PutObject"]
  resources = ["${aws_s3_bucket.statements.arn}/reports/*"]    # só escreve reports
}
```
Se o código da Lambda tiver uma vulnerabilidade, o estrago máximo é ler exports e escrever reports. Não é `s3:*` em `*`.

Três formas de dar credenciais, da pior para a melhor:
1. Access key fixa no código ou em variável de ambiente ✘
2. Access key num cofre ✔-
3. **Role assumida pela identidade do recurso** (Lambda role, instance profile na EC2, IRSA no EKS, OIDC no GitHub Actions) ✔✔: credenciais temporárias, rotacionadas automaticamente, nada para vazar.

A POC só usa a opção 3.

## 6. Terraform: infraestrutura como código

```
infra/terraform/
  versions.tf    provider AWS, versão, tags padrão, backend remoto (comentado)
  variables.tf   entradas (nome do bucket, VPC, tipo da EC2)
  s3.tf  lambda.tf  ec2.tf
  outputs.tf     saídas (ARN da Lambda, id da EC2)
```
Ciclo:
```bash
terraform init       # baixa os providers
terraform fmt        # formata
terraform validate   # valida a sintaxe e as referências
terraform plan       # mostra o que VAI mudar (revise sempre!)
terraform apply      # aplica
```
Conceitos:
| Conceito | O que é |
|---|---|
| `resource` | Algo que o Terraform cria e gerencia |
| `data` | Algo que ele só **lê** (AMI, documento de policy) |
| `variable`/`output` | Entradas e saídas do módulo |
| **State** | Arquivo com o mapeamento "recurso no código ↔ recurso real". **Nunca** versionar no Git (tem segredos); usar backend remoto (S3) com lock (DynamoDB) para duas pessoas não aplicarem ao mesmo tempo |
| `default_tags` | Toda peça criada leva `Project`/`Environment`: essencial para rastrear custo |

Validado na POC: `fmt` e `validate` passam. **Nunca foi aplicado** numa conta real.

Alternativas: CloudFormation/CDK (nativos AWS), Pulumi (IaC em linguagens de programação), OpenTofu (fork aberto do Terraform).

## 7. Custos (resumo da conversa)

Valores on-demand aproximados em us-east-1; confirme na AWS Pricing Calculator.

| Cenário | ~US$/mês |
|---|---|
| Stack inteira numa EC2 de 8 GB, ligada 24 h | 55 a 70 |
| A mesma, ligada só para demos | ~3 |
| Homologação com EKS + RDS + MSK + DocumentDB, sem HA | ~400 |
| Produção com HA em 3 zonas | ~1.450 |
| Produção com os bancos dentro do Kubernetes (operação por conta do time) | ~750 |

Lições:
- **Kafka e Mongo gerenciados** são ~60% do custo de produção. Os microsserviços são baratos.
- **NAT Gateway** é o custo escondido clássico (US$ 33/mês cada + US$ 0,045/GB). O VPC endpoint do S3 é **gratuito** e tira esse tráfego do NAT.
- **Instalar o próprio Kubernetes** em vez do EKS economiza no máximo ~US$ 60/mês, abrindo mão da alta disponibilidade do control plane.
- **Rodar os bancos dentro do cluster** economiza de verdade, mas transfere para o time backup, failover, upgrades e plantão. Faz sentido em dev/homologação, não em produção de um banco.
- **São Paulo (sa-east-1)** é 30 a 50% mais cara, mas pode ser exigida por residência de dados.
- **S3 e Lambda** nesse volume custam centavos. A Lambda tem 1 milhão de invocações gratuitas por mês.

---

## No seu ERP

- **XMLs de NF-e no S3:** a legislação exige guardar os documentos fiscais por anos (em geral, 5 anos). Guardar o XML no banco incha o Postgres. No S3, com lifecycle para Glacier depois de alguns meses, fica barato, durável e versionado. O banco guarda só a chave do objeto.
- **Lambda para processar arquivos recebidos:** retorno CNAB do banco, planilhas de importação, EDI de parceiros. O arquivo cai no S3 e a Lambda valida e grava numa tabela de staging. O ERP não precisa de um job de polling de pasta.
- **Backups do Postgres no S3** com retenção e criptografia (se não estiver em RDS, que já faz isso).
- **Tags de custo** em tudo, por módulo e cliente, desde o primeiro recurso.

---

## Exercícios

1. Liste o conteúdo do bucket local, baixe um CSV de `exports/` e o JSON correspondente de `reports/`. Confira os totais na mão.
2. Suba um arquivo com espaço no nome (`awslocal s3 cp arq.csv "s3://pocbank-statements/exports/conta teste/1.csv"`) e veja os logs da Lambda. Depois comente o `URLDecoder.decode` e repita.
3. Rode `terraform plan` com variáveis fictícias (vai falhar na autenticação; observe até onde vai). O que estaria faltando para aplicar numa conta vazia?
4. Escreva a policy IAM de menor privilégio para o statement-service (IRSA): quais ações e quais recursos?
5. Calcule o custo mensal de guardar 5 anos de XMLs do seu ERP no S3 Standard × Glacier Instant Retrieval (estime o volume de notas por mês e ~10 KB por XML).
