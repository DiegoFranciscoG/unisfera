# syntax=docker/dockerfile:1.7
# Imagen del frontend: compilación con Node LTS y servicio estático con nginx sin privilegios.

FROM node:26.11.1-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginxinc/nginx-unprivileged:1.31.2-alpine
COPY --chown=101:101 nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /app/dist/frontend/browser /usr/share/nginx/html
# Usuario sin privilegios de la imagen (uid 101), declarado explícitamente.
USER 101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
