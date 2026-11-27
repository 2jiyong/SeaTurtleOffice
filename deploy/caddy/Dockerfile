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

FROM caddy:2-builder AS caddy
RUN xcaddy build --with github.com/mholt/caddy-ratelimit

FROM caddy:2
COPY --from=caddy /usr/bin/caddy /usr/bin/caddy
COPY --from=frontend /src/dist /srv
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
