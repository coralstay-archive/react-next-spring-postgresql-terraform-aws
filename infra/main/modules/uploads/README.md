# uploads 모듈

이미지 첨부 버킷 (TASK-11). 브라우저가 앱이 발급한 presigned PUT URL 로 `uploads/{userId}/{uuid}.{ext}` 에 직접 올리고,
읽기는 CloudFront `/uploads/*` 동작(OAC)으로만 한다.

만드는 것:

- `aws_s3_bucket` + 퍼블릭 접근 차단 4종 + `BucketOwnerEnforced`(ACL 비활성) + SSE-S3(AES256, bucket key)
- CORS: `allowed_origins` 에서 `PUT`(헤더 `content-type`, `x-amz-tagging`, `if-none-match` — presign 이 서명한 헤더.
  `If-None-Match: *` 는 덮어쓰기 금지라 확정한 객체에 같은 URL 로 다시 PUT 하면 412), `GET`/`HEAD`
- lifecycle: `uploads/` 아래 `status=pending` 태그가 남은 객체를 `pending_expiration_days`(기본 1)일 뒤 만료,
  끝나지 않은 멀티파트 업로드는 1일 뒤 정리
- 버킷 정책: HTTPS 강제, `cloudfront_distribution_arn` 을 주면 그 배포(OAC)만 `uploads/*` 를 `s3:GetObject`

미확정 정리 흐름: 앱의 presign 이 `x-amz-tagging: status=pending` 을 서명에 넣으므로 모든 업로드에 태그가 붙는다.
`POST /api/attachments/{id}/confirm` 이 HeadObject 검증 뒤 태그를 지운다(DeleteObjectTagging). confirm 되지 않은 객체는
lifecycle 이, DB 의 PENDING 행은 앱 스케줄러(`app.uploads.pending-ttl`, 기본 24시간)가 지운다.

## 사용

```hcl
module "uploads" {
  source = "../../modules/uploads"

  bucket_name                 = "board-prod-uploads"
  allowed_origins             = ["https://d111.cloudfront.net"] # 앱의 PUBLIC_BASE_URL
  cloudfront_distribution_arn = aws_cloudfront_distribution.this.arn # TASK-16
  # force_destroy = true # 실습 환경을 destroy 하려면
}

# 앱(EC2 role)에 권한 부여
resource "aws_iam_role_policy" "uploads" {
  role   = aws_iam_role.app.id
  policy = module.uploads.app_policy_json
}
```

CloudFront: 오리진 `bucket_regional_domain_name` + OAC(sigv4, always), 동작 `/uploads/*` → 이 오리진(캐시 정책 CachingOptimized).
경로가 그대로 키가 된다(`/uploads/7/a.png` → `uploads/7/a.png`).

## 출력 → 앱 env

| 출력 | 쓰는 곳 |
|---|---|
| `bucket_name` | `UPLOADS_BUCKET` (`UPLOADS_REGION` 은 버킷 리전, `UPLOADS_PUBLIC_BASE_PATH` 는 기본 `/uploads/`) |
| `app_policy_json` | EC2 role 인라인 정책 (uploads/* Put/Get/Delete Object + Tagging) |
| `bucket_regional_domain_name`, `bucket_arn` | CloudFront 오리진·OAC (TASK-16) |

## 테스트

`tests/*.tftest.hcl` 은 `mock_provider "aws"` 로 AWS 자격 증명 없이 돈다. `make test`(scripts/test.sh)가
`terraform init -backend=false` 후 `terraform test` 를 실행한다. lock 파일은 커밋하지 않는다(.gitignore).
