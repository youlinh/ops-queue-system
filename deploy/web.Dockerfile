FROM node:22-alpine AS build
WORKDIR /src
ENV CI=true
# China-friendly registry for corepack's pnpm download and every package;
# remove these three lines to fall back to registry.npmjs.org.
ENV COREPACK_NPM_REGISTRY=https://registry.npmmirror.com
ENV COREPACK_INTEGRITY_KEYS=0
ENV npm_config_registry=https://registry.npmmirror.com
RUN corepack enable
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
