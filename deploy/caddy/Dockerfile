# 배포용 Caddy 이미지: 프론트 정적 파일 + 요청 제한 플러그인을 넣은 Caddy.
# 빌드 위치는 저장소 루트다(compose.prod.yaml). 루트 .dockerignore가 비밀 파일을 뺀다.
FROM node:22-alpine AS frontend
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend ./
# 방문 통계. 비어 있으면 프론트가 통계 스크립트를 넣지 않는다.
ARG VITE_UMAMI_SRC=""
ARG VITE_UMAMI_WEBSITE_ID=""
ENV VITE_UMAMI_SRC=$VITE_UMAMI_SRC VITE_UMAMI_WEBSITE_ID=$VITE_UMAMI_WEBSITE_ID
RUN npm run build

# 버전을 고정한다. 요청 제한 플러그인은 1.0 전이라 설정 문법이 바뀔 수 있다. 올릴 때는 로컬 시험(DEVELOPMENT.md)부터.
FROM caddy:2.11.9-builder AS caddy
RUN xcaddy build --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM caddy:2.11.9
COPY --from=caddy /usr/bin/caddy /usr/bin/caddy
COPY --from=frontend /src/dist /srv
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
# 점검 안내 페이지(deploy/maintenance.sh로 켠다)
COPY deploy/caddy/maintenance.html /maintenance-page/index.html
