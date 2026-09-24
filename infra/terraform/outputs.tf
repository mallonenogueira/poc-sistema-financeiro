output "statements_bucket" {
  value = aws_s3_bucket.statements.bucket
}

output "statement_report_lambda_arn" {
  value = aws_lambda_function.statement_report.arn
}

output "demo_host_instance_id" {
  description = "Use: aws ssm start-session --target <id>"
  value       = aws_instance.demo_host.id
}
