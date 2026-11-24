# M0 Prerequisites

Everything that must exist **before** the M0 build prompt can reach its definition of done
("an FCM push lands on a real Android phone"). Work top to bottom; the blocking items come first.

Machine checked: 2026-11-24.

---

## 0. Toolchain status on this machine

| Tool | Found | Verdict |
|---|---|---|
| Node | **v18.20.8** | ⚠️ **Too old.** Node 18 reached end-of-life in April 2025 and NestJS 11 requires Node ≥ 20. Upgrade to Node 22 LTS |
| npm | 10.8.2 | OK |
| Java | **openjdk 24.0.2** | ⚠️ **Too new for Android.** AGP targets JDK 17/21; JDK 24 will fail or behave unpredictably. Install JDK 21 LTS and point Gradle at it |
| Docker | **not installed** | ⚠️ **Blocking.** Local Postgres + Redis come from `docker compose` |
| keytool | `/usr/bin/keytool` | OK |
| adb | `~/Library/Android/sdk/platform-tools/adb` | OK — Android SDK is installed |
| `ANDROID_HOME` | **unset** | Minor. Android Studio works without it; CLI Gradle builds are happier with it set |
| Debug keystore | `~/.android/debug.keystore` | OK — fingerprints below |
| gcloud CLI | not installed | Optional — console works fine |
| firebase CLI | not installed | Optional — console works fine |

### Fixes

```bash
brew install --cask docker
```

```bash
brew install node@22
```

```bash
brew install --cask temurin@21
```

Then pin Gradle to JDK 21 by adding this to `~/.gradle/gradle.properties` (adjust the path if
your JDK lands elsewhere — check with `/usr/libexec/java_home -V`):

```bash
echo "org.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" >> ~/.gradle/gradle.properties
```

And add the Android SDK to your shell profile:

```bash
echo 'export ANDROID_HOME="$HOME/Library/Android/sdk"' >> ~/.zshrc
```

> **Note on Java:** leaving JDK 24 as your system default is fine — Gradle will use whatever
> `org.gradle.java.home` points at. You do not need to uninstall anything.

---

## 1. Debug signing fingerprints

Already extracted from your existing debug keystore. You need the **SHA-1** when creating the
Android OAuth client in step 3.

```
SHA-1    46:2B:C7:B7:36:3E:3E:17:2E:88:C0:33:BC:7E:8D:C5:78:DB:50:8D
SHA-256  45:26:B3:D0:96:7D:CB:EF:6F:9E:65:A7:90:76:78:46:05:47:30:F4:2B:D0:8C:40:A6:45:F6:E9:3B:AF:E5:76
```

Valid until Jan 2053, so it will outlast the project.

> ⚠️ This is the **debug** certificate. The release build is signed with a different key, so when
> you first build a release APK you must add that key's SHA-1 as a **second** Android OAuth client,
> or Google Sign-In will work in debug and fail in release. This catches almost everyone once.

To regenerate these later:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

---

## 2. Firebase project

Console: <https://console.firebase.google.com>

1. **Add project** → name `wishkaro`. Google Analytics is optional; you're using PostHog
   (`OD-10`), so you can decline it.
2. In the project, **Add app → Android**:
   - Package name: **`in.wishkaro.app`** — must match the Gradle `applicationId` exactly, and
     cannot be changed after a Play release.
   - Debug signing SHA-1: paste the SHA-1 from step 1.
3. Download **`google-services.json`** → place at `composeApp/google-services.json`.
   It is already gitignored — **leave it that way**, the repo is public.
4. **Project settings → Service accounts → Generate new private key.** This JSON is what the
   NestJS server uses with Firebase Admin to send pushes. Save it outside the repo and reference
   it from `.env`.

> The service-account key is the single most sensitive file in this project: it can send
> notifications to every user you ever have. It is gitignored, and GitHub push protection is
> enabled on the repo — but do not rely on either as your only defence.

---

## 3. Google OAuth client IDs

Console: <https://console.cloud.google.com/apis/credentials> — use the Google Cloud project that
Firebase created for you, not a new one.

First, **OAuth consent screen**: External, app name WishKaro, your support email, scopes
`email` + `profile` + `openid` only. Leave it in Testing mode for now and add yourself as a test
user — you do **not** need verification for these three non-sensitive scopes.

Then create **two** clients:

| Type | Needs | Used by |
|---|---|---|
| **Android** | package `in.wishkaro.app` + debug SHA-1 | The app, so Google will issue a token |
| **Web** | nothing extra | The **server**, as the audience it validates ID tokens against |

> The Web client is the one people forget. The Android app requests a token, but your NestJS
> backend verifies it against the **Web client ID** — that value goes in the server's `.env` as
> `GOOGLE_CLIENT_ID`. Without it, `POST /v1/auth/google` cannot validate anything and M0's first
> step fails.

---

## 4. Play Console account — start this now

<https://play.google.com/console/signup> · one-time USD 25.

This is on the list now and not at M9 because it is **calendar time you cannot compress**: a
personal developer account created after Nov 2023 must run a **closed test with at least 12
testers for 14 consecutive days** before it can even apply for production access. Identity
verification can also take several days on its own.

Starting it today costs you an hour and USD 25, and removes a multi-week block from the end of
the project. Start recruiting the 12 testers in parallel — friends and family count.

---

## 5. Hosting (`TBD-7`)

M0's definition of done includes a deployed server. **Railway** is the recommendation: Postgres
and Redis are one-click add-ons, deploys come straight from the GitHub repo, and it costs roughly
USD 5/month at this scale. Fly.io is cheaper at scale but you provision Postgres and Redis
yourself.

Sign up with the GitHub account that owns the repo. Nothing else to configure until M0 is ready
to deploy.

---

## 6. AI provider keys — **not needed for M0**

`gpt-image-1` and Anthropic keys are first used in **M6**. Do not create them yet. Creating keys
months before you need them just widens the window in which they can leak from a public repo.

---

## Readiness checklist

- [ ] Docker Desktop installed and running
- [ ] Node 22 installed and active (`node --version`)
- [ ] JDK 21 installed and `org.gradle.java.home` set
- [ ] `ANDROID_HOME` exported
- [ ] Firebase project created, Android app registered with `in.wishkaro.app` + debug SHA-1
- [ ] `google-services.json` at `composeApp/google-services.json` (gitignored)
- [ ] Firebase service-account key saved **outside** the repo
- [ ] OAuth consent screen configured, yourself added as a test user
- [ ] Android OAuth client created
- [ ] Web OAuth client created — client ID saved for the server's `.env`
- [ ] Play Console account created and identity verification started
- [ ] Railway account created
- [ ] A physical Android phone with USB debugging on, visible to `adb devices`

When every box is ticked, paste `docs/BUILD_PROMPT_M0.md` into a fresh Claude Code session.
