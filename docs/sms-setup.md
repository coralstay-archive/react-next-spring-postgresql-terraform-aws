# 휴대폰 OTP SMS 설정 — AWS End User Messaging SMS (TASK-18 AC#3)

운영에서 휴대폰 로그인 인증번호를 보내는 설정이다. 앱은 `SMS_PROVIDER=aws`이면 AWS End User Messaging SMS(API 이름 `pinpoint-sms-voice-v2`)의
`SendTextMessage`(메시지 유형 `TRANSACTIONAL`)를 EC2 인스턴스 role 자격 증명으로 호출한다. 앱 동작(3분 만료, 5회 실패 폐기, 발송 한도)은
[docs/api.md의 휴대폰 OTP 절](api.md#휴대폰-otp-로그인-task-7)에 있다.

> **확인 필요 표시**: SMS 규정은 나라와 통신사마다 다르고 AWS 정책도 자주 바뀐다. 이 문서에서 **(확인 필요)**로 표시한 항목은 진행하기 전에 AWS 콘솔의
> 해당 화면과 공식 문서(*AWS End User Messaging SMS User Guide*의 "Supported countries and regions", "SMS sandbox", "Spending quotas")로 확인한다.
> 이 문서는 절차의 순서와 앱 쪽 설정을 정리할 뿐, 한국 발신 규정을 확정하지 않는다.

## 이미 준비된 것 (Terraform·앱)

- IAM: 인스턴스 role에 `sms-voice:SendTextMessage`가 있다(`infra/main/modules/backend_host`, 발신 identity가 아직 정해지지 않아 `Resource = "*"`).
  발신 번호를 정한 뒤 그 ARN으로 좁혀도 된다.
- 리전: us-east-1(`SMS_AWS_REGION`, Terraform `app_env`가 넣는다). 아래 콘솔 작업도 **us-east-1**에서 한다. SMS 샌드박스·지출 한도·발신 identity는 리전마다 따로다.
- 남용 방지: 같은 번호 60초 1회·24시간 10회(`OTP_PHONE_DAILY_LIMIT`), 같은 IP 1시간 20회(`OTP_IP_HOURLY_LIMIT`). CloudFront 뒤라 IP는 `CLIENT_IP_HEADER=CloudFront-Viewer-Address`로 읽는다.

## SSM 키

| 키 | 값 | 비고 |
|---|---|---|
| `SMS_PROVIDER` | `aws` | `console`은 운영 금지(인증번호가 CloudWatch 로그에 남는다). 그 밖의 값이면 기동 실패 |
| `SMS_AWS_REGION` | `us-east-1` | Terraform `app_env`가 넣는다 |
| `SMS_ORIGINATION_IDENTITY` | 발신 번호(E.164), sender ID, phone pool ID·ARN, 또는 비움 | 비우면 요청에 넣지 않는다. 그러면 AWS가 계정의 identity나 공용 경로를 고른다 — 한국행에서 허용되는지 **(확인 필요)** |
| `SMS_CONFIGURATION_SET` | configuration set 이름(선택) | 전송 이벤트(성공·실패)를 CloudWatch·Firehose 등으로 남길 때 |
| `SMS_API_CALL_TIMEOUT` / `SMS_API_CALL_ATTEMPT_TIMEOUT` | `10s` / `4s`(선택) | SendTextMessage 호출 전체(재시도 포함)·시도별 상한(TASK-25#4). 넘으면 503 `sms_unavailable` |
| `SMS_DEFAULT_REGION` | `KR` | `+` 없이 들어온 번호를 E.164로 바꿀 때 쓰는 지역 |
| `SMS_ALLOWED_REGIONS` | `KR` | SMS를 보낼 수 있는 번호의 지역(쉼표로 여럿). 이 지역의 휴대폰만 보내고 그 밖(다른 나라·유선·070 등)은 발송 전 400 `phone_not_allowed` — 고가 국제 번호로 비용을 빼 가는 SMS pumping 방지(TASK-22#4). End User Messaging의 국가별 발송 허용(protect configuration)과 같이 맞춘다 |

```bash
# deploy/secrets.local.txt
SMS_PROVIDER=aws
SMS_ORIGINATION_IDENTITY=          # 2·3단계에서 정한 값
SMS_CONFIGURATION_SET=
make put-secrets && make deploy-backend
```

## 1. 샌드박스에서 먼저 시험

새 계정은 리전마다 **SMS 샌드박스** 상태다. 샌드박스에서는 다음과 같이 동작한다 **(확인 필요: 현재 한도)**.

- **검증된 대상 번호**(verified destination phone numbers)로만 보낼 수 있다(개수 제한 있음).
- 월 지출 한도가 작다(기본 USD 1 수준).

검증된 번호를 등록한다. 콘솔에서는 **AWS End User Messaging SMS → Sandbox(또는 Destination phone numbers) → Add destination phone number** 경로다. CLI로는 다음과 같다.

```bash
aws pinpoint-sms-voice-v2 create-verified-destination-number --destination-phone-number +8210XXXXXXXX --region us-east-1
# 출력의 VerifiedDestinationNumberId 로 인증 코드 SMS 를 받는다
aws pinpoint-sms-voice-v2 send-destination-number-verification-code \
  --verified-destination-number-id <id> --verification-channel TEXT --region us-east-1
aws pinpoint-sms-voice-v2 verify-destination-number \
  --verified-destination-number-id <id> --verification-code <받은 코드> --region us-east-1
```

그다음 운영 앱에서 확인한다.

1. `https://<cf>/login/` → 휴대폰 탭 → 검증한 번호 → **인증번호 받기** → SMS 도착 → 입력 → 로그인. 처음이면 PHONE 계정이 새로 생긴다.
2. 실패하면 화면에 `sms_unavailable`(503)이 뜬다. CloudWatch(`aws logs tail /board/prod --log-stream-name-prefix api/`)에서 `SendTextMessage` 예외를 본다.
   흔한 원인: 샌드박스인데 대상 번호를 검증하지 않음, 발신 identity가 그 나라에서 허용되지 않음, 지출 한도 초과, 리전 불일치.
3. 직접 보내 보려면 다음 명령을 쓴다(앱 없이 경로만 확인):
   ```bash
   aws pinpoint-sms-voice-v2 send-text-message --destination-phone-number +8210XXXXXXXX \
     --message-body "test" --message-type TRANSACTIONAL --region us-east-1 [--origination-identity <id>]
   ```

## 2. 한국(+82) 발신 경로 정하기 (확인 필요)

AWS 문서 "Supported countries and regions (SMS channel)"의 **South Korea (KR)** 행에서 다음 항목을 확인하고 경로를 고른다.

| 확인할 것 | 의미 |
|---|---|
| Sender ID 지원 여부와 **등록 필요 여부** | 문자 발신 이름(알파벳 sender ID)을 쓸 수 있는지, 쓰려면 사전 등록이 필요한지 |
| Long code / toll-free / short code 지원 여부 | 한국에서 받을 수 있는 발신 번호 종류 |
| 국제 발신 표시 | 해외 경로로 보낸 메시지는 수신 단말에 국제 발신 표시가 붙거나 발신 번호가 바뀔 수 있다 |
| 등록(registration) 필요 여부와 처리 기간 | 콘솔의 **Registrations**에서 해당 나라 양식이 있는지, 심사에 며칠이 걸리는지 |

진행 순서는 다음과 같다.

1. 콘솔 **Configurations → Phone numbers / Sender IDs → Request originator**에서 대상 나라를 **South Korea**로 고르면, 가능한 identity 종류와 필요한 등록이 표시된다.
2. 등록이 필요하면 **Registrations**에서 양식(회사·사용 사례·메시지 예시: `[board] 인증번호 123456 (3분 안에 입력하세요)`)을 제출하고 승인을 기다린다.
3. 승인된 identity(번호·sender ID 또는 pool)를 `SMS_ORIGINATION_IDENTITY`에 넣는다. 여러 나라로 보낼 계획이면 **phone pool**을 만들어 pool ID를 넣는다.
4. 한국 규정(예: 국내 발신 번호 사전 등록제, 스팸 필터, 인증 문자 형식)이 해외 A2P 경로에 어떻게 적용되는지는 AWS 문서와 AWS Support로 확인한다 **(확인 필요)**.
   국내 통신사 직접 연동(국내 문자 서비스)이 필요하면 `SmsSender` 구현을 하나 더 두는 방식으로 바꿀 수 있다(앱은 구현 교체를 설정으로 한다).

## 3. 샌드박스 해제 (production access)

1. 콘솔 **AWS End User Messaging SMS → Sandbox → Request production access**(또는 Support Center → *Service limit increase* → End User Messaging SMS)를 연다.
2. 입력할 내용: 리전 us-east-1, 대상 나라 South Korea, 메시지 유형 **Transactional**(OTP), 예상 월 발송량, 회원이 직접 요청할 때만 보낸다는 동의 방식(opt-in),
   메시지 예시, 남용 방지(번호당 60초·일 10회, IP당 시간 20회 한도), 웹사이트 URL(`PUBLIC_BASE_URL`).
3. 승인은 보통 수일이 걸린다 **(확인 필요)**. 승인되기 전까지는 검증된 번호로만 운영한다.

## 4. 지출 한도 (spend limit)

- 계정·리전 단위의 **월 SMS 지출 한도**가 있고, 넘으면 발송이 실패한다(앱은 503 `sms_unavailable`).
- 한도를 올리려면 Support 케이스로 쿼터를 올리고, 콘솔 **Account settings → Spend limits**에서 승인된 범위 안으로 설정한다 **(확인 필요: 기본값·절차)**.
- 권장: 예상 발송량 × 건당 단가 × 2 정도로 잡는다. **AWS Budgets** 알림(End User Messaging 서비스 필터)도 함께 건다.
- 앱의 발송 한도는 한 번호와 한 IP가 쓸 수 있는 양만 막는다. 여러 IP로 나눠 보내는 공격은 지출 한도와 Budgets 알림으로 감지한다.
  필요하면 CloudFront에 WAF 속도 제한(`/api/auth/phone/otp`)을 추가한다(후속 작업).

## 5. 비용 메모

- **건당 요금**: 대상 나라와 경로에 따라 다르다. 한국행 transactional SMS 단가는 AWS 요금 페이지(End User Messaging SMS pricing, South Korea)에서 확인한다 **(확인 필요)**.
  긴 메시지(한글은 UCS-2라 70자를 넘으면 분할)는 여러 건으로 과금된다. 현재 메시지는 한 건 길이 안에 들어간다.
- **발신 identity 월 요금**: 전화번호(long code·toll-free 등)는 매달 요금이 붙는다. 일부 나라는 sender ID 등록에도 요금이 있다 **(확인 필요)**.
- **등록 비용**: 나라별 등록 양식에 수수료가 있을 수 있다 **(확인 필요)**.
- 정리할 때 Terraform은 이 자원을 지우지 않는다. 번호 release, 등록 철회, pool 삭제를 콘솔에서 직접 한다([first-deploy.md 12단계](first-deploy.md#12-정리-destroy-순서)).

## 체크리스트

- [ ] us-east-1에서 대상 번호 검증 → 운영 앱 휴대폰 로그인 성공(샌드박스)
- [ ] South Korea 행 확인: sender ID·번호 종류·등록 필요 여부 → 경로 결정
- [ ] (필요하면) Registrations 제출 → 승인 → `SMS_ORIGINATION_IDENTITY`
- [ ] production access 요청 → 승인
- [ ] 지출 한도 상향 + AWS Budgets 알림
- [ ] `SMS_PROVIDER=aws` 확인(`console` 아님) → `make put-secrets` → `make deploy-backend`
- [ ] 검증하지 않은 실제 번호로 로그인 성공(샌드박스 해제 뒤)
