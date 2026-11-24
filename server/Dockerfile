# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY prisma ./prisma
RUN npx prisma generate

COPY tsconfig*.json nest-cli.json ./
COPY src ./src
RUN npm run build

# Drop dev dependencies, but keep the Prisma CLI: `migrate deploy` runs at container
# start, so the image must be able to apply migrations without a dev toolchain.
RUN npm prune --omit=dev && npm install prisma@^6 --no-save

# ---- runtime --------------------------------------------------------------
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production

RUN apk add --no-cache tini curl
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY --from=build /app/prisma ./prisma
COPY package.json ./

USER node
EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://127.0.0.1:${PORT:-3000}/health || exit 1

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["sh", "-c", "npx prisma migrate deploy && node dist/main.js"]
