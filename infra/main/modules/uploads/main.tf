# 이미지 첨부 버킷 (TASK-11#4). 브라우저가 앱이 발급한 presigned PUT URL 로 uploads/{userId}/{uuid}.{ext} 에 직접 올리고,
# 읽기는 CloudFront /uploads/* (OAC) 로만 한다. 퍼블릭 접근은 모두 막는다.
#
# 미확정 정리: presign 이 x-amz-tagging: status=pending 을 서명에 넣어 모든 업로드에 태그가 붙고, confirm 이 태그를 지운다.
# lifecycle 은 태그가 남은 객체를 pending_expiration_days 뒤에 지운다. DB 의 PENDING 행은 앱 스케줄러가 지운다.

locals {
  uploads_prefix = "uploads/"
  pending_tag    = { status = "pending" }
  # presign 이 서명하는 헤더 중 브라우저가 preflight 에 올리는 것(Content-Length·Host 는 브라우저가 붙인다)
  # if-none-match: 덮어쓰기 금지(TASK-26#2) — 이미 있는 키에 다시 PUT 하면 412 라 확정 객체에 pending 태그가 다시 붙지 않는다
  signed_headers = ["content-type", "x-amz-tagging", "if-none-match"]

  bucket_policy = {
    Version = "2012-10-17"
    Statement = concat(
      [{
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.this.arn, "${aws_s3_bucket.this.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      }],
      var.cloudfront_distribution_arn == null ? [] : [{
        Sid       = "AllowCloudFrontReadUploads"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.this.arn}/${local.uploads_prefix}*"
        Condition = { StringEquals = { "AWS:SourceArn" = var.cloudfront_distribution_arn } }
      }],
    )
  }

  # 앱(EC2 인스턴스 프로파일)에 붙일 권한. presigned URL 은 앱 자격 증명으로 서명되므로 PutObject·PutObjectTagging 도 앱에 필요하다.
  app_policy = {
    Version = "2012-10-17"
    Statement = [{
      Sid    = "BoardUploads"
      Effect = "Allow"
      Action = [
        "s3:PutObject", "s3:PutObjectTagging", "s3:GetObject", "s3:GetObjectTagging", "s3:DeleteObject", "s3:DeleteObjectTagging",
      ]
      Resource = "${aws_s3_bucket.this.arn}/${local.uploads_prefix}*"
      }, {
      # TASK-26#1 ListBucket 이 없으면 S3 는 없는 키의 HeadObject 에 404 대신 403 을 준다(키 존재 여부를 숨김) → PUT 전 confirm 이
      # 400 attachment_not_uploaded 가 아니라 503 storage_unavailable 이 된다. HeadObject 요청에는 s3:prefix 가 없어 접두어 조건을
      # 붙이면 적용되지 않으므로 조건 없이 이 버킷에만 준다 — 이 버킷에는 uploads/ 객체뿐이라 노출 범위는 같다
      Sid      = "BoardUploadsList"
      Effect   = "Allow"
      Action   = "s3:ListBucket"
      Resource = aws_s3_bucket.this.arn
    }]
  }
}

resource "aws_s3_bucket" "this" {
  bucket        = var.bucket_name
  force_destroy = var.force_destroy
  tags          = var.tags
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_cors_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  # 브라우저 직접 업로드(presigned PUT)
  cors_rule {
    allowed_methods = ["PUT"]
    allowed_origins = var.allowed_origins
    allowed_headers = local.signed_headers
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }

  # 이미지 읽기(보통은 CloudFront 같은 origin 이라 필요 없지만 canvas 등 CORS 읽기용)
  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = var.allowed_origins
    allowed_headers = []
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "expire-pending-uploads"
    status = "Enabled"

    filter {
      and {
        prefix = local.uploads_prefix
        tags   = local.pending_tag
      }
    }

    expiration {
      days = var.pending_expiration_days
    }
  }

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {
      prefix = ""
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = jsonencode(local.bucket_policy)

  # 퍼블릭 차단이 먼저 있어야 정책 검사(퍼블릭 정책 거부)가 의도대로 동작한다
  depends_on = [aws_s3_bucket_public_access_block.this]
}
