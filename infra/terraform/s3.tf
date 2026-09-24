resource "aws_s3_bucket" "statements" {
  bucket = var.statements_bucket_name
}

resource "aws_s3_bucket_public_access_block" "statements" {
  bucket                  = aws_s3_bucket.statements.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "statements" {
  bucket = aws_s3_bucket.statements.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_versioning" "statements" {
  bucket = aws_s3_bucket.statements.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Extratos exportados são dados pessoais (LGPD): retenção limitada.
resource "aws_s3_bucket_lifecycle_configuration" "statements" {
  bucket = aws_s3_bucket.statements.id

  rule {
    id     = "expire-exports"
    status = "Enabled"
    filter { prefix = "exports/" }
    expiration { days = 30 }
  }

  rule {
    id     = "archive-reports"
    status = "Enabled"
    filter { prefix = "reports/" }
    transition {
      days          = 90
      storage_class = "GLACIER_IR"
    }
  }
}
