# Build with the repository root as context (the pnpm workspace files live there):
#   docker build -f services/api/Dockerfile .
# infra/docker-compose.yml does exactly that.
FROM node:24-alpine AS base
WORKDIR /repo
# The pnpm version comes from the packageManager field of the root package.json.
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY services/api/package.json services/api/

FROM base AS build
RUN pnpm install --frozen-lockfile --filter @snipnet/api...
COPY services/api services/api
RUN pnpm --filter @snipnet/api run build

FROM base AS prod-deps
RUN pnpm install --frozen-lockfile --prod --filter @snipnet/api...

FROM node:24-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY --from=prod-deps /repo/node_modules ./node_modules
COPY --from=prod-deps /repo/services/api/node_modules ./services/api/node_modules
COPY --from=build /repo/services/api/dist ./services/api/dist
COPY services/api/package.json ./services/api/
COPY services/api/migrations ./services/api/migrations
WORKDIR /app/services/api
USER node
EXPOSE 3000
# Migrations run on startup, so a fresh database needs no separate step.
CMD ["node", "dist/server.js"]
