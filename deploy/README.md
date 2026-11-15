# 배포 안내

아키텍처 정의서의 배포 구성(AWS EKS · RDS · S3 · EFS, Jenkins → BuildKit → Harbor → Helm → Argo CD)을 그대로 따른다.

| 환경 | 방법 | 검증 상태 |
|---|---|---|
| 로컬 (Docker) | `docker compose up -d --build` | 이 저장소에서 실행해 E2E 통과 확인 |
| 로컬 쿠버네티스 (kind / Docker Desktop) | Helm 차트 + `values-local.yaml` | 이 저장소에서 설치해 E2E 통과 확인 |
| AWS EKS | Helm 차트 + `values-eks.yaml`, Argo CD | 차트는 lint·렌더링까지 확인. **실제 AWS 계정 배포는 미검증** (자격 증명 필요) |

## 1. 로컬 (Docker Compose)

```bash
cp .env.example .env        # 선택: JEV_API_KEY, OPENAI_API_KEY
docker compose up -d --build
# http://localhost:8088  — employee1 / hr1 / admin1, 비밀번호 demo1234
python tools/e2e_demo.py --base http://localhost:8088 --evaluate   # 데모 시나리오 + 정답 세트 검증
```

## 2. 로컬 쿠버네티스

```bash
docker compose build
kind create cluster --name evidence --config deploy/kind/cluster.yaml
kind load docker-image evidence/frontend:local evidence/backend:local evidence/ai-server:local --name evidence
helm upgrade --install evidence deploy/helm/evidence-agent -n evidence --create-namespace \
  -f deploy/helm/evidence-agent/values-local.yaml --wait --timeout 10m
# http://localhost:30088
python tools/e2e_demo.py --base http://localhost:30088
```

- Docker가 cgroup v1을 쓰는 PC(구형 WSL2 커널)에서는 최신 노드 이미지가 뜨지 않는다. `kind create cluster … --image kindest/node:v1.34.11` 처럼 1.34 이하 이미지를 지정한다.
- 이 환경에서 확인한 것: 설치·재설치(시크릿 유지), 파드 7개 Running(비루트 · 읽기 전용 루트 FS), PVC 바인딩, E2E 13개 확인 통과, 내부 토큰 없이 AI 서버 호출 시 401.
- 확인하지 못한 것: NetworkPolicy 집행. 정책은 적용되지만 kind의 CNI가 이 PC 커널에서 nftables 동기화에 실패해 차단되지 않았다. EKS에서는 VPC CNI의 네트워크 정책 기능을 켜야 집행된다(`bootstrap.sh` 2단계).

## 3. AWS EKS

### 3.1 한 번만: 기반 자원

`deploy/aws/bootstrap.sh` 가 아래를 만든다(과금 자원). 한 단계씩 실행하며 확인하는 것을 권한다.

| 자원 | 용도 | 차트 값 |
|---|---|---|
| EKS 클러스터 + AWS Load Balancer Controller | 파드 실행, ALB Ingress | `ingress.*` |
| S3 버킷 (공개 차단 · SSE · 버전 관리) | 증빙 원본 · 미리보기 · 규정집 원문 | `s3.bucket` |
| IAM 역할 `evidence-agent-backend` (IRSA) | 백엔드 파드만 버킷 접근 | `serviceAccount.annotations` |
| EFS + StorageClass `efs-sc` | Qdrant 영구 볼륨 | `qdrant.persistence.storageClass` |
| RDS PostgreSQL 16 (비공개 · 암호화) | 신청 · 검수 이력 · 감사 로그 | `postgres.host` |
| Secret `evidence-agent-secrets` | JWT 키, 내부 토큰, DB 비밀번호, Jev/OpenAI 키 | `secrets.existingSecret` |

```bash
AWS_REGION=ap-northeast-2 CLUSTER=evidence BUCKET=<유일한-버킷-이름> DB_PASSWORD=<강한-비밀번호> \
JEV_API_KEY=<선택> OPENAI_API_KEY=<선택> ./deploy/aws/bootstrap.sh
```

추가로 필요한 것: Harbor 프로젝트(`evidence`)와 로봇 계정, 이미지 풀 시크릿, ACM 인증서, Argo CD, Jenkins.

```bash
kubectl -n evidence create secret docker-registry harbor-pull \
  --docker-server=<harbor-host> --docker-username=<robot> --docker-password=<token>
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

### 3.2 배포

1. `deploy/helm/evidence-agent/values-eks.yaml` 의 `<...>` 값을 채운다(Harbor 주소, RDS 엔드포인트, 버킷, 역할 ARN, 도메인, 인증서 ARN).
2. `deploy/argocd/application.yaml` 을 적용한다. 저장소가 private이므로 먼저 Argo CD에 저장소 자격 증명을 등록한다(`argocd repo add https://github.com/zser99/evidence-review-agent.git --username <계정> --password <토큰>`).

```bash
kubectl apply -n argocd -f deploy/argocd/application.yaml
kubectl -n evidence get pods,ingress        # ALB 주소 확인 → Route 53 에 연결
```

3. 이후로는 `main` 에 push 하면 Jenkins가 테스트 → 이미지 빌드·push → `values-eks.yaml` 의 `image.tag` 커밋까지 하고, Argo CD가 그 커밋을 감지해 롤링 배포한다.

Argo CD 없이 직접 올릴 때:

```bash
helm upgrade --install evidence deploy/helm/evidence-agent -n evidence --create-namespace \
  -f deploy/helm/evidence-agent/values-eks.yaml --set image.tag=<태그> --wait
```

### 3.3 배포 뒤 확인

```bash
kubectl -n evidence rollout status deploy/evidence-backend deploy/evidence-ai-server deploy/evidence-frontend
python tools/e2e_demo.py --base https://<도메인> --evaluate
```

- 화면 오른쪽 위 배지가 `Jev · …` 이면 Jev로, `대체 모델 · …` 이면 대체 모델로 판정 중이다.
- 결정 모델이나 모델 버전을 바꾸면 **검증 결과 → 정답 세트 검증 실행**으로 확신도 구간별 정답률을 다시 재고 `decision.auto_threshold` 를 확인한다.

### 3.4 운영 전 점검

- [ ] `config.seedDemoUsers: false` (데모 계정 제거), `APP_DEMO_PASSWORD` 삭제
- [ ] 실제 증빙을 받기 전에 주관 팀 보안 기준 확인: 외부 API(Jev, OpenAI)로 나가는 값은 조건의 `inputs` 에 적은 항목뿐이고 성명·주민번호·계좌번호는 마스킹되지만, **진료 내용·교육기관명 같은 판단 대상 값은 나간다**
- [ ] 사내 전용이면 `alb.ingress.kubernetes.io/scheme: internal`
- [ ] RDS 백업 보존 기간, S3 수명 주기(보존 기한) 정책
- [ ] 대학알리미 OpenAPI 키를 받으면 `config.universityApiUrl` 과 `UNIVERSITY_API_KEY` 를 넣고 응답 필드명을 확인(`UniversityDirectory.search`)

### 3.5 정리 (과금 중지)

```bash
kubectl delete -n argocd -f deploy/argocd/application.yaml
aws rds delete-db-instance --db-instance-identifier evidence-db --skip-final-snapshot
aws efs delete-file-system --file-system-id <fs-id>      # 마운트 타깃을 먼저 지운다
aws s3 rb s3://<버킷> --force
eksctl delete cluster --name evidence --region ap-northeast-2
```

## 구성요소와 파드

| 파드 | 이미지 | 역할 | 외부 통신 |
|---|---|---|---|
| `frontend` ×2 | Nginx + Vue 빌드 결과 | 정적 파일, `/api` 리버스 프록시 | 없음 |
| `backend` ×2 | Spring Boot | 업무 규칙, 대조·정적 규칙, 최종 판정, 이력 | RDS, S3, (대학알리미) |
| `ai-server` ×1 | FastAPI | OCR·항목 추출, 규정 검색, 결정 모델 질의 | Jev, OpenAI (HTTPS) |
| `qdrant` ×1 | Qdrant | 규정 조항 벡터 색인 (EFS) | 없음 |
| `redis` ×1 | Redis | 대학 정보 조회 캐시 | 없음 |

NetworkPolicy로 구성도의 화살표만 연다(외부 → frontend → backend → ai-server → qdrant, backend → redis). 모든 파드는 비루트 · 읽기 전용 루트 파일시스템 · 권한 상승 금지로 실행한다.
