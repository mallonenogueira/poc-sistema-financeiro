#!/bin/bash
# Executado pelo LocalStack quando fica pronto (ready.d).
set -euo pipefail

awslocal s3 mb s3://pocbank-statements || true
echo "Bucket pocbank-statements pronto"
