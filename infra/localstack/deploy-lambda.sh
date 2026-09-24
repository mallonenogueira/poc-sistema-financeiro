#!/bin/bash
# Implanta a Lambda statement-report no LocalStack e liga o gatilho S3 (exports/*.csv).
# Uso: docker compose exec localstack bash /opt/pocbank/deploy-lambda.sh
set -euo pipefail

JAR=/opt/pocbank/lambda/statement-report-lambda.jar
FN=pocbank-statement-report
BUCKET=pocbank-statements

if [ ! -f "$JAR" ]; then
  echo "Jar não encontrado em $JAR. Rode o build Maven (mvn -pl lambda/statement-report-lambda -am package) antes."
  exit 1
fi

awslocal lambda delete-function --function-name "$FN" >/dev/null 2>&1 || true
awslocal lambda create-function \
  --function-name "$FN" \
  --runtime java21 \
  --handler br.com.pocbank.lambda.StatementReportHandler::handleRequest \
  --role arn:aws:iam::000000000000:role/pocbank-statement-report \
  --zip-file "fileb://$JAR" \
  --timeout 60 \
  --memory-size 512 \
  --environment "Variables={S3_FORCE_PATH_STYLE=true}" >/dev/null
awslocal lambda wait function-active-v2 --function-name "$FN"

ARN=$(awslocal lambda get-function --function-name "$FN" --query 'Configuration.FunctionArn' --output text)
awslocal s3api put-bucket-notification-configuration --bucket "$BUCKET" --notification-configuration "{
  \"LambdaFunctionConfigurations\": [{
    \"LambdaFunctionArn\": \"$ARN\",
    \"Events\": [\"s3:ObjectCreated:*\"],
    \"Filter\": {\"Key\": {\"FilterRules\": [
      {\"Name\": \"prefix\", \"Value\": \"exports/\"},
      {\"Name\": \"suffix\", \"Value\": \".csv\"}
    ]}}
  }]
}"
echo "Lambda $FN implantada e ligada a s3://$BUCKET/exports/*.csv"
