# Build context is the repository root, because the pnpm workspace lockfile lives there:
#   docker build -f apps/web/Dockerfile .
FROM node:24-alpine AS build
WORKDIR /repo
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
COPY services/api/package.json services/api/package.json
RUN pnpm install --frozen-lockfile --filter @snipnet/web...
COPY apps/web apps/web
RUN pnpm --filter @snipnet/web run build

FROM nginx:1.27-alpine
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/apps/web/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
