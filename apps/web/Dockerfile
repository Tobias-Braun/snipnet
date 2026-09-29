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
# Vite inlines VITE_* variables at build time, so the API base URL for the waitlist form is a build argument.
ARG VITE_API_URL=
ENV VITE_API_URL=$VITE_API_URL
RUN pnpm --filter @snipnet/web run build

# The unprivileged variant runs nginx as a non-root user, which is why it listens on 8080 instead of 80.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /repo/apps/web/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
