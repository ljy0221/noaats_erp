# ============================================================
# IFMS frontend — Vite build → nginx:alpine 정적 서빙
# ============================================================

# ---- build stage ----
FROM node:20-alpine AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json* /workspace/
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

COPY frontend /workspace
RUN npm run build

# ---- runtime stage ----
FROM nginx:alpine AS runtime
# Alpine TZ
RUN apk add --no-cache tzdata \
 && ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone

COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80
# nginx:alpine 기본 CMD 사용 (nginx -g 'daemon off;')
