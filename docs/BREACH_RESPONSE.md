# Breach response

> §12's thirteenth box: *"Breach response note in `/docs`."* It has no `FR-` id, which is
> exactly why it would have been missed — a plan that quotes requirements by id silently skips
> every rule that does not have one (`MILESTONE_CHECKLIST.md`, bought by M5 §0.6).
>
> **Status:** written at M9, against what the deployment actually contains on 2026-11-27. It
> is a one-person plan for a one-person product, not a corporate policy. It is worth having
> because the first twenty minutes of a breach are spent deciding what to do, and that is the
> one part that can be decided in advance.
>
> **Owner:** Dipen Jansari. There is nobody else to escalate to, which is a fact about this
> project rather than an omission in this document.

---

## 1. What there is to lose

The honest inventory, because a response plan written against an imagined system is worse
than none.

| Asset | Where it lives | What its loss means |
|---|---|---|
| `person`, `event`, `person_trait`, `wish` rows | Postgres on Railway | Names, phone numbers, email addresses, relations, notes, dates of birth and death, and the text of messages people sent. **The most sensitive thing this product holds.** A death anniversary is a fact about a bereavement. |
| Source photos | Blob store (`BLOB_STORE=local` → the container's disk) | Photographs of named, identifiable real people, with EXIF stripped on upload but faces intact. |
| Posters | Same | A face, a name and an occasion composited into one image. |
| `user` rows | Postgres | Email address, Google `sub`, display name, timezone. |
| `device` rows | Postgres | FCM tokens — a capability to push a notification to somebody's phone. |
| `refresh_token` rows | Postgres | Hashes, not tokens (`sha256`, `auth.service.ts`). A dump does not yield a usable session, but it does yield the shape of the session table. |
| `ai_usage`, `generation` | Postgres | Costs and model names. No prompt text, no user content. |
| Secrets | Railway's env store, and `~/.secrets/` on one laptop | `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, `BLOB_SIGNING_SECRET`, the Firebase service account, and `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` **if either is ever configured**. Both of the latter bill a personal card. |

**Not held:** payment details (Play holds those, M7 or not), passwords (Google holds those),
location (EXIF is stripped on upload — `photo.db.spec.ts` asserts it), analytics (PostHog is
in `OD-10` and has never been built, so there is nothing in it).

---

## 2. The four shapes a breach here would actually take

Ranked by how likely they are for *this* system, not by how dramatic they sound.

### 2.1 A secret committed to a public repository

**The most likely one by a distance**, and the one `REQUIREMENTS.md` warns about at the top of
the document. The repo is public, public repos are scraped continuously by credential bots,
and a pushed secret is compromised the moment it is pushed.

**Do this, in this order:**

1. **Rotate at the provider.** Not `git rm`. Deleting the commit does not unpublish it — the
   object stays reachable, forks keep it, and caches have it. A key that has been pushed is a
   key somebody else has.
   - `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` → revoke in the provider console, **then** check
     the usage dashboard for spend that is not yours.
   - Firebase service account → delete the key in the Google Cloud console, issue a new one,
     update `FIREBASE_SERVICE_ACCOUNT` on Railway.
   - `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET` → generate new ones and set them on Railway.
     This signs every session out, which is the point. Nobody loses data: the local database
     survives and the next sign-in re-syncs.
   - `BLOB_SIGNING_SECRET` → rotate. Every outstanding signed URL dies; they live ten minutes
     anyway (`SIGNED_URL_TTL_SECONDS`).
2. Only then remove it from the working tree and `.gitignore` the path.
3. Check GitHub **secret scanning** alerts — and if push protection is not switched on for the
   repository, switch it on now. §11 asks for it.
4. Record it in §4 of this document.

### 2.2 The Railway deployment or its database is reached

The API is public, authentication is a short-lived JWT, and the Postgres instance is Railway's.

1. In the Railway dashboard: **rotate the database credentials** and redeploy.
2. Rotate both JWT secrets — this invalidates every live session, including the attacker's.
3. `DELETE FROM refresh_token` — one statement, and it ends every long-lived session rather
   than waiting out `JWT_REFRESH_TTL` (60 days).
4. Read the deploy and access logs for the window. Note what they cannot tell you: there is no
   per-row audit log in this schema, so "which people were read" is not a question this system
   can answer. Say that in the notification rather than implying a narrower blast radius than
   is known.
5. Assume **every row for every user** is exposed until something specific says otherwise.

### 2.3 A user's own account is taken over

Google Sign-In is the only route in (`D5`), so this is an account takeover **at Google**, not
here. There is nothing to rotate on our side for that user beyond ending their sessions.

1. `UPDATE refresh_token SET revoked_at = now() WHERE user_id = ...` — their sessions end.
2. Tell them to secure the Google account; we cannot do it for them.
3. If they want the data gone, that is FR-SET-04's deletion path, which is what M9 builds.

### 2.4 Blob objects exposed

Every object is served behind a ten-minute HMAC signature (`MediaController`, `@Public`), and
the signature covers the key *and* the expiry so neither can be edited independently.

1. Rotate `BLOB_SIGNING_SECRET`. Every outstanding URL is dead immediately.
2. Note the known weakness honestly: **theme** blob keys are derived from shipped data and are
   therefore predictable, which is why `BLOB_SIGNING_SECRET` has a 32-character floor (M6
   §0.7). Poster and photo keys are random UUIDs and are not guessable.

---

## 3. Telling people

India's **DPDP Act** makes a personal-data breach notifiable to the Data Protection Board
**and** to each affected person, without a materiality threshold — so the question is not
whether it is bad enough to report, only how fast.

**Decide within one hour of confirming it:**

1. **Contain first.** Rotate, revoke, redeploy. An hour spent drafting a notice while the hole
   is open is an hour the hole is open.
2. **Write down what is known and what is not**, separately. The same discipline every
   `Mx_VERIFY.md` in this repo uses: *proved*, *read-verified*, *unknown*. A notification that
   rounds "we cannot tell" up to "no evidence of access" is the thing that turns a breach into
   a credibility problem.
3. **Notify the Board**, with: what happened, when, what categories of data, how many people,
   what has been done, and what the person should do.
4. **Notify every affected user**, by the email address on their `user` row, in plain language,
   saying specifically whether photographs and dates of birth were involved — because those are
   the parts people care about and the parts a generic notice obscures.
5. If `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` was involved, that is **not** a personal-data
   breach. It is a bill. Handle it as §2.1 and do not conflate the two in the notice.

**What we can honestly say about scope.** There is no per-row read audit. The system can say
what it *holds* and what the window was; it cannot say which rows were read. Say exactly that.

---

## 4. Incident log

Nothing to record. New entries go at the top, dated, with what was rotated and when the
notification went out.

| Date | What | What was rotated | Notified |
|---|---|---|---|
| — | — | — | — |

---

## 5. What would make this plan better, and is not built

Named rather than implied, so nobody reads this document as a description of a system that has
these things:

- **No per-row access audit**, so breach scope can never be narrowed below "everything this
  user had". The nearest thing in the schema is `ai_usage`, which records cost, not reads.
- **`BLOB_STORE=local` is not durable** (`isDurable = false`) and Railway replaces the
  container on deploy, so there is no byte-level history to examine after the fact — and
  equally, no long-lived store to breach.
- **No alerting.** §11 asks for "queue depth and job-failure alerting"; there is a `/health`
  endpoint and nothing watching it. A breach would be noticed by a person, probably late.
- **No staging environment.** A rotation is rehearsed in production or not at all.
- **Sentry is in §14's stack list and is not configured**, so there is no error telemetry to
  read during an incident.
