variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "statements_bucket_name" {
  description = "Nome globalmente único do bucket de extratos"
  type        = string
}

variable "lambda_jar_path" {
  description = "Jar sombreado gerado por `mvn -pl lambda/statement-report-lambda package`"
  type        = string
  default     = "../../lambda/statement-report-lambda/target/statement-report-lambda.jar"
}

variable "ec2_instance_type" {
  type    = string
  default = "t3.medium"
}

variable "vpc_id" {
  description = "VPC onde a EC2 de demonstração será criada"
  type        = string
}

variable "subnet_id" {
  description = "Subnet (privada, acesso via SSM) para a EC2"
  type        = string
}
