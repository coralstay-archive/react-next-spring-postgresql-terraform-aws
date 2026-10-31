<!-- work-order-hash: 5c5a6f17309864a87a3011a8fc0ffafc860319106faf1b231464da2a46ffd458 -->
# 작업 순서

`work_order.py`가 backlog/tasks의 의존(`dependencies`)으로 만든 파일이다 — 직접 고치지 말고 다시 생성한다. 같은 단계의 태스크는 병렬로, 단계는 위에서 아래로 순차로 진행한다. Done 태스크와 없는 태스크에 대한 의존은 충족된 것으로 본다.

| 단계 | ID | 제목 |
|---|---|---|
| 1 | TASK-36 | [플랫폼] VM k3s 로컬 플랫폼 부트스트랩 |
| 1 | TASK-66 | [비용] Infracost 로컬·CI |
| 1 | TASK-68 | source-header 적용: toy-project/react-next-spring-postgresql-terraform-aws |
| 2 | TASK-37 | [플랫폼] 프론트 Next 서버 분리 |
| 2 | TASK-43 | [플랫폼] 관측성: Prometheus |
| 2 | TASK-46 | [프로토콜] 이벤트 백본: outbox + Kafka |
| 3 | TASK-38 | [보안] 백엔드 하드닝: CORS·헤더·인증·세션·로그 |
| 3 | TASK-39 | [보안] 프론트 하드닝: CSP·헤더·SSR 방어 |
| 3 | TASK-40 | [보안] 공급망·CI 보안 |
| 3 | TASK-41 | [플랫폼] GitOps 매니페스트 + Argo CD core |
| 3 | TASK-45 | [배포] 섀도우: Traefik 미러링 |
| 3 | TASK-53 | [관측] eBPF 에이전트 기반 + pod별 네트워크 건강 모니터 |
| 4 | TASK-42 | [플랫폼] CI/CD 파이프라인 |
| 4 | TASK-44 | [배포] 카나리: Argo Rollouts + Traefik |
| 4 | TASK-47 | [프로토콜] gRPC + Connect(gRPC-Web) |
| 4 | TASK-48 | [프로토콜] GraphQL + JSON-RPC |
| 4 | TASK-49 | [프로토콜] WebSocket(STOMP) + SSE |
| 4 | TASK-50 | [프로토콜] Webhook + MQTT |
| 4 | TASK-54 | [관측] eBPF 런타임 보안 감시 모듈 |
| 4 | TASK-55 | [관측] eBPF 지연 프로파일러 모듈 |
| 4 | TASK-60 | [운영] 백업·복구·무중단 |
| 4 | TASK-63 | [운영] 개인정보·법규 |
| 5 | TASK-56 | [검진센터] 기반 서비스 + 종합 건강 점검 |
| 5 | TASK-62 | [운영] 배포 안전장치 |
| 6 | TASK-57 | [검진센터] 자동 진단 스냅샷 |
| 6 | TASK-61 | [운영] SLO·알림·런북 |
| 7 | TASK-58 | [검진센터] 인시던트 관리 + 런북 |
| 8 | TASK-51 | [보안] 전체 보안 점검 |
| 9 | TASK-52 | [AWS] k3s 스팟 노드 실배포(나중 단계) |
