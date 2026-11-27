# 4단계: EC2 배포 + HTTPS 설계

## 왜

지금까지 만든 게임(①DB·기기 세션, ②로그·이벤트, ③남용 방지)을 실제 사람들이 쓸 수 있게 인터넷에 올린다. 혼자 운영하는 작은 서비스라서 **싸고, 단순하고, 문제가 생겼을 때 알 수 있는** 쪽을 고른다. 나중에 응답 속도와 사용량을 비교할 수 있게 ②에서 만든 로그·이벤트가 배포 환경에서도 그대로 쌓여야 한다. 방문자 수(DAU 등)는 Umami로 본다.

## 목표

- `https://www.seaturtleoffice.site`에서 게임이 돈다. `seaturtleoffice.site`는 www로 보낸다.
- `https://stats.seaturtleoffice.site`에서 Umami(방문자 통계)를 본다.
- 인증서는 자동 발급·갱신(Caddy).
- 배포는 서버에서 한 줄: `deploy/deploy.sh` (= `git pull --ff-only` + `docker compose up -d --build`).
- 로그는 JSON 한 줄씩(백엔드 ECS, AI `LOG_FORMAT=json`) 도커 로그에 남고, 디스크를 채우지 않게 크기 상한이 있다.
- 매일 DB 백업(`pg_dump`), 14일 보관.
- CPU 크레딧이 바닥나거나 서버가 응답하지 않으면 메일이 온다. 하드웨어 문제면 자동 복구.

## 하지 않는 것

- CI/CD(GitHub Actions), 이미지 저장소. (방식 A로 결정. 나중에 B로 옮길 때 Dockerfile은 그대로 쓴다.)
- 서버 여러 대, 로드밸런서, RDS, 무중단 배포. 배포 중 백엔드가 다시 뜨는 10~30초 동안 끊겨도 된다.
- 서버 밖(S3 등) 백업. 지금은 같은 디스크에만 둔다. (CLI 사용자에게 IAM 권한이 없어 인스턴스 역할을 못 붙인다. 위험은 아래 "남는 위험"에.)
- 사용량 화면(⑤단계).

## 하면 안 되는 것 (기술적으로 돌아가도 실패)

- 비밀 값(OpenAI 키, DB 비밀번호, `IP_HASH_SECRET`, Umami `APP_SECRET`)이 저장소에 들어가는 것. 저장소는 **공개**다.
- 원본 IP가 로그·DB에 남는 것. (③의 HMAC 해시만.)
- 사용자님의 개발 DB 컨테이너(`sea-turtle-office-postgres-1`)나 볼륨을 건드리는 것. 배포용 compose는 다른 프로젝트 이름을 쓴다.
- SSH(22)를 전 세계에 여는 것.
- 사용자에게 묻지 않고 돈이 드는 AWS 자원을 만들거나 지우는 것.

## 방식 (사용자 결정: A)

| | A. 서버에서 직접 빌드 (선택) | B. CI가 빌드한 이미지를 받기 |
|---|---|---|
| 배포 | `git pull && docker compose up -d --build` | Actions → GHCR → `compose pull && up -d` |
| 장점 | 준비할 것이 거의 없다 | 서버 부담 없음, 배포 몇 초 |
| 단점 | 2GB 서버에서 빌드 2~4분, 그동안 느림, 스왑 필요 | 워크플로·레지스트리 권한 설정 |

A를 고른 이유: 혼자 운영하고 배포가 잦지 않다. 2GB 서버의 빌드 부담은 스왑 2GB로 받는다.

## 구성

```
사용자 ─HTTPS─▶ caddy :80 :443(tcp/udp)
                ├ www.seaturtleoffice.site   → 정적 프론트(/srv) + /api/* → backend:8080
                ├ seaturtleoffice.site       → 301 www
                └ stats.seaturtleoffice.site → umami:3000
               backend → ai:8000 → OpenAI
               postgres:17 (DB seaturtle + DB umami)
```

밖으로 열리는 포트는 caddy의 80/443뿐이다. postgres·backend·ai·umami는 도커 내부망에서만 보인다.

### 서버 (AWS, 서울 ap-northeast-2)

- EC2 `t4g.small`(ARM, 2 vCPU, 2GB), **CPU 크레딧 `standard` 모드**(`--credit-specification CpuCredits=standard`; 기본값 unlimited는 크레딧이 바닥나도 계속 달리며 초과분을 청구한다. standard는 기준치 20%로 느려질 뿐 추가 요금이 없다 — 빌더 판단), Ubuntu 24.04 LTS arm64(AMI는 SSM 공개 파라미터로 최신값 조회), gp3 20GB, IMDSv2 필수, 종료 방지 켬, 태그 `Name=seaturtle-office`.
- 고정 IP(Elastic IP) 1개.
- 보안 그룹 `seaturtle-office`: 80/tcp, 443/tcp, 443/udp를 0.0.0.0/0·::/0에, 22/tcp는 사용자님의 현재 공인 IP/32에만.
- SSH 허용 IP가 바뀌면(집·카페 이동) 로컬에서 `deploy/ssh-allow-my-ip.sh`를 돌린다: 현재 공인 IP를 조회해 보안 그룹의 22번 규칙을 그 IP/32 하나로 바꾼다(AWS CLI, 빌더 판단).
- 키 페어 `seaturtle-office`: 만들 때 받은 .pem을 `~/.ssh/seaturtle-office.pem`(권한 400)에 둔다. 저장소에는 두지 않는다.
- 첫 설정 `deploy/bootstrap.sh`(서버에서 한 번): Docker 공식 apt 저장소로 docker-ce·compose 플러그인 설치, 스왑 2GB, 시간대 Asia/Seoul, 저장소 clone(`/opt/seaturtle`), 백업 cron 등록.
- 모든 AWS 생성·변경은 명령과 예상 비용을 보여 주고 허락받은 뒤 CLI로 실행한다. 조회는 묻지 않는다.

### DNS (가비아, 사용자가 직접)

A 레코드 3개 → Elastic IP: `@`, `www`, `stats`. TTL 600. Caddy를 처음 켜기 전에 넣고 `dig`로 퍼진 것을 확인한다(인증서 발급 실패가 반복되면 Let's Encrypt가 잠시 막는다).

### 파일

| 파일 | 하는 일 |
|---|---|
| `compose.prod.yaml` | 배포 전체. `name: sea-turtle-office-prod`. 5개 서비스, `restart: unless-stopped`, 메모리 상한, 로그 크기 상한, 헬스체크 |
| `.env.example` (루트) | 배포 `.env`의 틀. 실제 `.env`는 서버에만 (이미 `.gitignore`에 있음) |
| `backend/Dockerfile` | 2단계: JDK 25로 `./gradlew bootJar -x test` → JRE 25 실행 |
| `ai/Dockerfile` | python 3.12 slim + uv, `uv sync --frozen --no-dev`, `uvicorn app.main:app --host 0.0.0.0 --no-access-log` |
| `deploy/caddy/Dockerfile` | 3단계: node로 프론트 빌드(Umami 값은 build arg) → `xcaddy build --with github.com/mholt/caddy-ratelimit` → caddy 이미지에 바이너리·`dist`·Caddyfile |
| `deploy/caddy/Caddyfile` | 아래 "Caddy" |
| `deploy/postgres-init/01-umami.sh` | 볼륨이 처음 만들어질 때 `umami` 사용자·DB 생성 |
| `deploy/deploy.sh` | `git pull --ff-only`, `APP_VERSION=$(git rev-parse --short HEAD)`, `docker compose -f compose.prod.yaml up -d --build`, 오래된 이미지 정리 |
| `deploy/backup.sh` | `pg_dump -Fc` 두 DB → `/opt/seaturtle-backups/날짜-db.dump`, 14일 지난 것 삭제. cron 매일 04:00 |
| `deploy/bootstrap.sh` | 위 "첫 설정" |
| `deploy/ssh-allow-my-ip.sh` | (로컬에서) 보안 그룹 22번을 현재 공인 IP로 바꾼다 |
| `deploy/smoke.sh` | 배포 뒤 확인(아래 "확인") |
| `DEVELOPMENT.md` | "배포" 절: 첫 설정, 배포, 로그 보기, 백업·복원, 경보 |

caddy 이미지는 `frontend/`가 필요해 **저장소 루트를 빌드 위치**로 쓴다. 그래서 루트 `.dockerignore`가 `**/.env`(특히 실제 키가 든 `ai/.env`), `**/node_modules`, `frontend/dist`, `backend/build`, `**/.venv`, `.git`, `.worktrees`를 반드시 뺀다. backend·ai는 각자 폴더를 빌드 위치로 쓰고 같은 목록의 `.dockerignore`를 둔다.

### 내용 파일(world/, scenarios/)

이미지에 넣지 않고 서버의 저장소 checkout에서 읽기 전용으로 마운트한다: `./world:/content/world:ro`, `./scenarios:/content/scenarios:ro`. 백엔드 `GAME_CONTENT_DIR=/content`, AI `GAME_CONTENT_DIR=/content`. 두 서버 모두 내용을 **시작할 때 한 번만** 읽는다(`ContentRepository` 생성자, AI `lifespan`). 그래서 `deploy.sh`는 빌드 뒤 항상 `up -d --force-recreate backend ai`로 둘 다 다시 띄운다. 시나리오만 바꾼 배포도 `deploy.sh` 한 번으로 끝난다(빌더 판단).

### 서비스별 설정

- **postgres**: `postgres:17`, 볼륨 `pgdata`, 비밀번호는 `.env`. 포트를 밖으로 열지 않는다. `pg_isready` 헬스체크.
- **backend**: `DB_URL=jdbc:postgresql://postgres:5432/seaturtle`, `DB_USER`, `DB_PASSWORD`(postgres와 같은 `.env` 값), `AI_BASE_URL=http://ai:8000`, `COOKIE_SECURE=true`, `SERVER_FORWARD_HEADERS_STRATEGY=native`(Caddy가 넘긴 `X-Forwarded-For`로 실제 IP를 보고 해시한다; Tomcat 기본 신뢰 대역에 도커 내부망 172.16/12가 들어 있다), `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `IP_HASH_SECRET`, `APP_VERSION`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70`. postgres가 healthy일 때 시작.
- **ai**: `OPENAI_API_KEY`, `LOG_FORMAT=json`, `GAME_CONTENT_DIR=/content`. uvicorn 접속 로그는 끈다(백엔드 접속 로그가 요청 ID와 함께 이미 남는다 — ②의 남은 문제 "uvicorn 접속 로그가 JSON이 아님" 해결).
- **umami**: 버전을 고정한 공식 postgres 이미지(arm64 지원 확인), `DATABASE_URL=postgresql://umami:…@postgres:5432/umami`, `APP_SECRET`. 첫 로그인 뒤 기본 관리자 비밀번호를 바로 바꾼다.
- **caddy**: 80/443(tcp·udp) 공개, 인증서 볼륨 `caddy_data`.

메모리 상한(합 1.7GB 안쪽, 나머지는 OS·빌드, 넘치면 스왑): backend 640m, ai 256m, postgres 384m, umami 320m, caddy 128m.

로그: 모든 서비스 `json-file`, `max-size: 10m`, `max-file: 5`.

### Caddy

- `{$SITE_DOMAIN}`, `{$APEX_DOMAIN}`, `{$STATS_DOMAIN}`은 `.env`에서 받는다(로컬 시험에서 `localhost` 이름으로 바꾸기 위해).
- 프론트: `/srv`에서 정적 파일. 라우팅은 해시(`#/game/…`)라서 SPA fallback이 필요 없다. `/assets/*`는 `Cache-Control: public, max-age=31536000, immutable`, `index.html`은 `no-cache`.
- `/api/*` → `backend:8080`, `Cache-Control: no-store`. 들어온 `X-Request-Id`는 버리고 Caddy가 새로 만든 값(`{http.request.uuid}`)으로 덮어 보낸다. 사용자가 고른 ID로 로그 연결이 오염되지 않게(빌더 판단). Caddy는 들어온 `X-Forwarded-For`를 믿지 않고 실제 접속 IP로 다시 쓴다(기본 동작; `trusted_proxies`를 설정하지 않는다).
- 초당 요청 제한(IP 기준 `{remote_host}`): 판정 경로(`POST /api/games/*/questions`, `/guesses`) 분당 60회, 그 밖의 `/api/*` 분당 120회. 한 사람은 판정이 2~3초 걸려 분당 20회를 넘기 어렵다. 60회는 통신사 공용 IP(CGNAT)·학교 와이파이 뒤 여러 명을 막지 않으면서 폭주만 끊는 값이다(빌더 판단; 하루 한도는 ③의 기기 200·IP 600이 맡는다). 넘으면 429와 `{"detail":"요청이 너무 잦습니다. 잠시 뒤 다시 시도해 주세요."}`(프론트가 `detail`을 그대로 보여 준다). ③의 남은 문제 "한도에 걸린 스팸도 DB에 기록된다"를 앞단에서 줄인다.
- 공통 헤더: `Strict-Transport-Security: max-age=31536000`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `-Server`.
- 접속 로그는 켜지 않는다(원본 IP가 남는다). 오류만 남긴다.

### Umami 연동 (프론트)

- 빌드 값 `VITE_UMAMI_SRC`(`https://stats.seaturtleoffice.site/script.js`), `VITE_UMAMI_WEBSITE_ID`. 둘 중 하나라도 비면 스크립트를 넣지 않는다(개발·테스트는 그대로).
- 스크립트는 `data-auto-track="false"`로 넣고 직접 기록한다. 해시 주소를 정규화해 판 ID가 통계에 남지 않게: `#/` → `/`, `#/game/{id}` → `/game`, `#/game/{id}/result` → `/result`.
- 이벤트: `case_open`(사건 열기, 시나리오 slug), `case_solved`, `case_closed`(못 풀고 끝남), `case_reset`. 데이터에는 slug만, 판 ID·질문 내용은 넣지 않는다.
- `window.umami`가 없으면(차단·미설정) 아무것도 하지 않는다.
- 첫 배포 순서: Umami를 먼저 켜 웹사이트를 등록해 ID를 받고 → `.env`에 넣고 → `deploy.sh`로 프론트를 다시 빌드.

### 경보 (CloudWatch + SNS, 무료 범위)

- SNS 주제 `seaturtle-alerts`에 사용자님 메일 구독(메일로 온 확인 링크를 눌러야 동작).
- `CPUCreditBalance < 30`이 30분 → 메일. (standard 모드라 크레딧이 바닥나면 CPU가 기준치 20%로 묶인다. 요금은 늘지 않는다.)
- `CPUUtilization > 80%`가 15분 → 메일.
- `StatusCheckFailed_System` 2분 → 자동 복구(`arn:aws:automate:ap-northeast-2:ec2:recover`) + 메일.
- `StatusCheckFailed_Instance` 3분 → 메일.
- 메모리·디스크는 CloudWatch 에이전트 없이 SSH에서 `docker stats`, `free -h`, `df -h`로 본다. `DEVELOPMENT.md`에 명령을 적는다.

## 확인

- 맥(Apple Silicon = 서버와 같은 arm64)에서 `compose.prod.yaml`을 `localhost` 이름(`www.localhost`, `localhost`, `stats.localhost`)으로 띄워 시험한다. Caddy가 로컬 인증서를 쓴다. 개발 DB와 프로젝트 이름·볼륨이 달라 서로 건드리지 않는다. 시험 뒤 시험용 스택만 내린다.
- `deploy/smoke.sh <사이트주소>`가 확인하는 것: 사이트 200, 앞 도메인 → www 301, `/api/info` 200 + `Cache-Control: no-store`, 처음 `/api/games` 응답의 `sto_client` 쿠키에 `Secure; HttpOnly`, stats 200, 판정 경로를 빠르게 61번 때리면 429와 JSON `detail`. (판정 대신 존재하지 않는 판 ID로 보내 OpenAI 비용 없이 시험.)
- 백엔드 로그 한 줄이 JSON이고 `requestId`가 있으며, AI 로그의 같은 요청 ID와 이어진다. `events.ip_hash`가 도커 내부 IP 하나로 모이지 않고 접속 IP마다 달라진다.
- 프론트 Umami 연동은 단위 테스트(정규화 주소, 이벤트, umami 없을 때 무동작).

## 남는 위험

- 백업이 같은 디스크에 있다. 디스크가 통째로 망가지면 잃는다. 나중에 S3나 EBS 스냅샷(DLM)으로 옮긴다(IAM 권한 필요).
- 서버 빌드 중 2~4분 느려진다.
- 단일 서버라 서버가 죽으면 서비스도 멈춘다(자동 복구 경보로 일부 완화).

## 빌더에게 맡기는 것

Dockerfile 세부(레이어 캐시 배치), 헬스체크 명령, Umami 고정 버전 고르기, smoke 스크립트 세부, `.dockerignore` 목록.
