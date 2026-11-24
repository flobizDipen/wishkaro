# M0 Prerequisites

Everything that must exist **before** the M0 build prompt can reach its definition of done
("an FCM push lands on a real Android phone"). Work top to bottom; the blocking items come first.

Machine checked: 2026-11-24.

---

## 0. Toolchain status on this machine

| Tool | Found | Verdict |
|---|---|---|
| Node | v18.20.8 → **v22.23.5** | ✅ Fixed. Managed by **fnm**, not Homebrew. Node 18 is EOL and below NestJS 11's minimum |
| npm | 10.8.2 | OK |
| Java | 24.0.2 active; **21.0.8 already installed** | ✅ Fixed. AGP does not support JDK 24. Microsoft OpenJDK 21 was already on the machine — Gradle now pinned to it, system default left alone |
| Docker | cask registered, **app missing** | ⚠️ Was uninstalled manually: `/Applications/Docker.app` gone, stale root-owned symlink left at `/usr/local/bin/docker` from Jun 2023. Reinstalling |
| keytool | `/usr/bin/keytool` | OK |
| adb | `~/Library/Android/sdk/platform-tools/adb` | OK — Android SDK is installed |
| `ANDROID_HOME` | unset → **set** | ✅ Fixed. Exported in `~/.zshrc` along with `platform-tools` and `emulator` on PATH |
| Debug keystore | `~/.android/debug.keystore` | OK — fingerprints below |
| gcloud CLI | not installed | Optional — console works fine |
| firebase CLI | not installed | Optional — console works fine |

### What was actually done

Node is managed by **fnm** on this machine, and JDK 21 was **already installed** — so the obvious
Homebrew commands would have been wrong on both counts. For the record:

```bash
fnm install 22 && fnm default 22
```

Gradle pinned to the existing Microsoft OpenJDK 21 in `~/.gradle/gradle.properties`:

```
org.gradle.java.home=/Users/dipenjansari/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home
```

Android SDK exported in `~/.zshrc`:

```
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Docker Desktop reinstalled:

```bash
brew reinstall --cask docker-desktop
```

> **Java:** leaving JDK 24 as the system default is fine — Gradle uses whatever
> `org.gradle.java.home` points at. Nothing was uninstalled.
>
> **Open a new terminal** for the Node and `ANDROID_HOME` changes to apply; existing shells keep
> the old values.

### ⚠️ Credential hygiene

`~/.gradle/gradle.properties` holds a GitHub personal access token in plaintext (`gpr.key`, for
GitHub Packages). That file is outside any repo so it is **not** committed — but the token is
long-lived and plaintext, so:

- **Rotate it** at <https://github.com/settings/tokens> if it has been exposed anywhere.
- Never copy those two lines into a project-level `gradle.properties` — that is the exact path by
  which these tokens end up in public git history.
- Prefer a fine-grained token scoped to `read:packages` only, with an expiry date.

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

**You do not create these by hand in Google Cloud Console.** Firebase provisions both of them
for you as a side effect of two settings. Creating them manually in Cloud Console produces
clients that Firebase does not know about, and they will not appear in `google-services.json`.

Verified live from the Firebase API (not a cached download):

```
project_id   : wishkaro-1c95e
app id       : 1:97088713716:android:ed09fd08c8168c140cffa1
package_name : in.wishkaro.app                            ✅
api_key      : present                                    ✅
SHA-1        : 462bc7b7363e3e172e88c033bc7e8dc578db508d   ✅ registered
oauth_client : 0 entries                                  ❌
```

**The SHA-1 is correctly registered and the OAuth clients are still absent.** That rules out the
fingerprint as the cause and isolates it to one thing: the Google sign-in *provider* has never
been enabled. Registering a fingerprint alone does not cause Firebase to emit OAuth clients into
`google-services.json` — enabling the provider is what provisions them.

### 3a. Debug SHA-1 — ✅ already done

```
46:2B:C7:B7:36:3E:3E:17:2E:88:C0:33:BC:7E:8D:C5:78:DB:50:8D
```

Registered against the Android app. Nothing further needed here until you add a **release**
signing key, which needs its own fingerprint added the same way.

### 3b. Enable Google as a sign-in provider → creates the **Web** client

Firebase Console → **Authentication** → **Get started** → **Sign-in method** → **Google** →
enable → set a project support email → Save.

Enabling the provider is what makes Google auto-create the **Web client**, which is the one your
NestJS server validates ID tokens against.

### 3c. Pull the config from the CLI, not the browser

The Firebase CLI is installed and authenticated, so skip the download-and-move dance entirely:

```bash
firebase apps:sdkconfig ANDROID 1:97088713716:android:ed09fd08c8168c140cffa1 --project wishkaro-1c95e -o composeApp/google-services.json
```

It should then show **2 oauth_client entries** — one `ANDROID` (client_type 1) and one `WEB`
(client_type 3). Copy the **Web** client ID into `server/.env` as `GOOGLE_CLIENT_ID`.

### CLI accounts

The project lives under a **personal** Google account, not the work one:

| Account | Sees `wishkaro` |
|---|---|
| `dipen@flobiz.in` | ❌ no — 15 other projects |
| `drjansari@gmail.com` | ✅ yes — now the global default |

Switch between them with `firebase login:use <email>`. If a Firebase command ever reports the
project as missing, this is why — check the active account first.

> Why the Web client and not the Android one: the app asks Google for a token, but the *server*
> must verify it. Google issues the ID token with the **Web** client as its audience, so that is
> what the backend checks. Using the Android client ID server-side fails every request, and the
> error message does not tell you why.

### Consent screen

Only needed once, and usually auto-created with the project. Check
<https://console.cloud.google.com/apis/credentials/consent> — External, app name WishKaro, your
support email, scopes `email` + `profile` + `openid` only, yourself added as a test user. Those
three scopes are non-sensitive, so **no verification review is required**.

---

## 4. Play Console account — deferrable, with a deadline

<https://play.google.com/console/signup> · one-time USD 25.

**Deferred by decision (2026-11-24).** It blocks nothing in M0–M8: you can build, run on a real
device, and test the entire app without it. Only publishing needs it.

The catch is that it is not work, it is **waiting**. A personal developer account created after
Nov 2023 must run a **closed test with at least 12 testers for 14 consecutive days** before it
can even apply for production access, and identity verification takes several days on its own.
That is roughly **3–4 weeks of wall-clock time** that cannot be compressed by working harder.

**Rule of thumb:** start it no later than **one month before** you want to be live, and ideally
at the start of M6 so it runs in the background while you build the AI features. Recruiting the
12 testers is the slow part — friends and family count, but they must actually install.

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

- [x] Docker Desktop installed
- [x] Node 22.23.5 installed and set as fnm default
- [x] JDK 21 present and `org.gradle.java.home` pinned to it
- [x] `ANDROID_HOME` exported in ~/.zshrc
- [x] Firebase project `wishkaro-1c95e` created, Android app registered as `in.wishkaro.app`
- [x] `google-services.json` at `composeApp/` with both OAuth clients (gitignored)
- [x] Firebase service-account key at `~/.secrets/` (chmod 600, outside repo), wired into `server/.env`
- [x] OAuth consent screen (auto-created with the project)
- [x] Android OAuth client provisioned
- [x] Web OAuth client provisioned; id in `server/.env` as GOOGLE_CLIENT_ID
- [ ] Play Console account — deferred by decision; start by M6
- [ ] Railway account created
- [x] Physical Android device connected and visible to `adb` (4B201JEBF03493)

When every box is ticked, paste `docs/BUILD_PROMPT_M0.md` into a fresh Claude Code session.
