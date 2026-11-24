# WishKaro server

NestJS + PostgreSQL (Prisma) + Redis/BullMQ. See `docs/M0_VERIFY.md` for the end-to-end
walkthrough and `docs/M0_PLAN.md` for why things are shaped the way they are.

## Run it

```bash
cp .env.example .env     # then fill in the real values
docker compose -f ../infra/docker-compose.yml up -d db redis
npx prisma migrate dev
npm run start:dev
```

Or the whole stack in containers:

```bash
docker compose -f ../infra/docker-compose.yml up --build
```

## Endpoints in M0

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | public; includes a real database round-trip |
| POST | `/v1/auth/google` | exchanges a Google ID token for a session |
| POST | `/v1/auth/refresh` | rotating refresh token |
| POST | `/v1/auth/logout` | |
| POST | `/v1/auth/dev-token` | development only, double-gated |
| GET/POST/DELETE | `/v1/people` | |
| GET | `/v1/events/upcoming` | |
| POST/DELETE | `/v1/events` | |
| POST | `/v1/devices` | registers an FCM token and the device timezone |
| POST | `/v1/reminders/test` | queues a reminder two minutes out |
| GET/POST | `/v1/sync` | delta pull and push |

Everything except `/health` and the `/v1/auth/*` routes requires `Authorization: Bearer`.

## Secrets

This repository is **public**. `.env` and any service-account JSON are gitignored and must
stay that way. Firebase credentials load either from `GOOGLE_APPLICATION_CREDENTIALS` (a file
path, local) or `FIREBASE_SERVICE_ACCOUNT` (raw or base64 JSON in the variable, for hosts with
no filesystem). The env var wins when both are set.
