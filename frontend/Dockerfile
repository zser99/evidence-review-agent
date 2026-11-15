# 프런트엔드 (Vue 빌드 결과 + Nginx 리버스 프록시) — front pod
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30-alpine
# /etc/nginx/templates/*.template 은 컨테이너 시작 시 환경변수(BACKEND_URL)로 치환된다
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV BACKEND_URL=http://backend:8080
EXPOSE 8080
