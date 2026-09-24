data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "statement_report" {
  name               = "pocbank-statement-report-${var.environment}"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "statement_report_logs" {
  role       = aws_iam_role.statement_report.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Menor privilégio: lê apenas exports/ e escreve apenas reports/.
data "aws_iam_policy_document" "statement_report_s3" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.statements.arn}/exports/*"]
  }
  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.statements.arn}/reports/*"]
  }
}

resource "aws_iam_role_policy" "statement_report_s3" {
  role   = aws_iam_role.statement_report.id
  policy = data.aws_iam_policy_document.statement_report_s3.json
}

resource "aws_lambda_function" "statement_report" {
  function_name    = "pocbank-statement-report-${var.environment}"
  role             = aws_iam_role.statement_report.arn
  runtime          = "java21"
  handler          = "br.com.pocbank.lambda.StatementReportHandler::handleRequest"
  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)
  memory_size      = 512
  timeout          = 30
  architectures    = ["arm64"]

  # SnapStart reduz drasticamente o cold start de funções Java.
  snap_start {
    apply_on = "PublishedVersions"
  }
  publish = true

  tracing_config {
    mode = "Active"
  }
}

resource "aws_lambda_permission" "allow_s3" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.statement_report.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.statements.arn
}

resource "aws_s3_bucket_notification" "exports_trigger" {
  bucket = aws_s3_bucket.statements.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.statement_report.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "exports/"
    filter_suffix       = ".csv"
  }

  depends_on = [aws_lambda_permission.allow_s3]
}
