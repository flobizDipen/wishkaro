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

# tini and curl: process reaping and the healthcheck.
#
# The fonts are M6's, and without them posters render as empty boxes rather than failing.
# `sharp` has no text API — FR-POS-01(c)'s copy is drawn by compositing an SVG, which
# librsvg rasterises through fontconfig. `node:22-alpine` ships NO fonts at all, so every
# headline would come out blank and nothing would throw.
#
# `font-noto-devanagari` is not optional politeness: FR-WSH-03 allows Hindi and Hinglish and
# poster copy inherits that, so a headline reading "जन्मदिन मुबारक" needs Devanagari coverage
# or it renders as tofu. `font-noto` covers Latin, and `fontconfig` is what lets the
# font-family stack in `compositor.ts` resolve at all.
RUN apk add --no-cache tini curl fontconfig font-noto font-noto-devanagari
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
