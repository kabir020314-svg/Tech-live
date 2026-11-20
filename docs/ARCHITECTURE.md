# Tech Live — Production Multi-Tier Architecture

## 1. Repository Structure
- `app/` — Kotlin + Jetpack Compose + Material 3 Android Client (`com.aistudio.techlive.rxkmpq`)
- `firestore.rules` & `firestore.test.js` — Zero-trust Firestore security rules & emulator verification suite
- `backend/` — Node.js / Express / TypeScript REST API (`schema.sql` with 19 PostgreSQL tables, OAuth, Resumable Uploads, Payments, RBAC Admin API)
- `worker/` — Cloud FFmpeg Worker (`ffmpegWorker.ts`) executing 7-day (`168h`) looped & scheduled RTMP/RTMPS broadcasts with exponential-backoff reconnect
- `admin-web/` — Standalone Web Admin Console SPA (`index.html`)

## 2. Role-Based Access & Data Ownership
- Normal users access only their own `users/{uid}` subcollections (`destinations`, `presets`, `videos`, `streams`, `schedules`, `notifications`), can read `system_settings/global`, and can create `payments` strictly with `status == 'PENDING'`.
- Admin UI entries and reactive listeners in `TechLiveViewModel` are gated behind `isAdminUser` (`SUPER_ADMIN` / `ADMIN` role or verified admin email `kabir020314@gmail.com`) and enforced server-side in `firestore.rules` and `/backend/src/server.ts`.

## 3. Daily 3-Hour Quota & Subscription Ledger Integrity
- **Free Plan**: Enforces dynamic `freeDailyLimitSeconds` (default `10800s` = `03:00:00`/day), auto-resets daily on UTC date rollover, tracks elapsed usage during live camera/screen broadcasts, warns at `< 15m`, blocks new broadcasts at `00:00:00`, and presents Monthly/Lifetime upgrade options.
- **Payments & Withdrawals**: Never activated client-side. Payments enter `PENDING`, transition to `PAID` / `REJECTED` / `REFUNDED` only via Admin Verification or authenticated Payment Webhooks, and Admin Withdrawal balance is computed strictly as `SUM(PAID) - SUM(REFUNDED) - SUM(committed withdrawals)`.

## 4. Platform Compliance
- **YouTube & Facebook**: Official OAuth flow routed through `/api/v1/oauth/{provider}/url` with backend-encrypted refresh tokens.
- **TikTok**: Respects official TikTok Live API availability (`TIKTOK_LIVE_API_ENABLED`) and cleanly offers Custom RTMP/RTMPS fallback without unofficial scraping or restriction bypassing.
- **Google Play Storage Policy**: Zero broad storage permissions (`READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` omitted); uses Android `PickVisualMedia` photo/video picker + foreground services (`camera`, `microphone`, `mediaProjection`, `dataSync`).

## 5. Secrets & Deployment Checklist
1. Deploy `firestore.rules` to Firebase project `kabir-tech-lab` (database `ai-studio-android-techlive-f04a9111-c8e9-4b07-9913-6298acf9335c`).
2. Provision PostgreSQL with `backend/db/schema.sql`, configure `.env` from `backend/.env.example`, and deploy `/backend` behind TLS.
3. Deploy `/worker` on compute instances with `ffmpeg` installed and access to S3/R2 video storage.
4. Set `TECHLIVE_API_BASE_URL` in AI Studio Secrets or update it live inside the app (`Profile -> Cloud Worker Backend URL`).
