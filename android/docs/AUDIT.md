# Implementation audit log

Root review accompanies the three Sol 6.1 Max writers. Findings are closed only with the relevant final code and verification evidence. Baseline before Android implementation: `c7fc76e` (upstream reference `faf1815e`). Physical camera acceptance is separate from emulator/build checks.

| ID | Finding and requirement | Owner | Status |
| --- | --- | --- | --- |
| R1 | Export must not replace existing output, including zero-byte files or a concurrent engine's result | media_engine | Initial hardlink solution passes JVM but fails Android SELinux; exclusive-create copy/fsync correction in progress |
| R2 | Export validation must check tracks, duration and canvas, not just file existence | media_engine | Correction requested; fixture instrumentation pending |
| R3 | A valid manifest with missing media must preserve its exact edit/history, not trigger rollback to an older manifest | domain_data | Correction in progress |
| R4 | Stale edits constructed later in wall-clock time must not overwrite a newer divergent edit | domain_data | Persisted revision ancestry approved; tests pending |
| R5 | Failure after moving a finalized source must be recoverable through the app, exactly once | domain_data/app_ui | Durable commit receipt and pending-media recovery in progress |
| R6 | Warning persistence failure on a full disk must not hide other valid projects | domain_data | In-memory warning fallback requested |
| R7 | Full-history backup revisions need bounded metadata retention; preserve original media and damaged evidence | domain_data | Five valid manifest revisions requested |
| R8 | Async source resolution/player errors must be visible and must not crash Compose | app_ui | Active-source-only resolution and player listener correction requested |
| R9 | Foreground service start/quota/receipt failures require a final visible job state | app_ui | Error-path corrections in progress |
| R10 | Export receipt must identify its project revision so an older render is distinguishable after edits | app_ui | Revision linkage in progress |
| R11 | Camera REC state begins on actual Start event; closing capture may yield usable interrupted output | media_engine | Starting flag added; Finalize classification under review |
| R12 | Manifest writer and reader limits must agree; rejecting an oversized edit must preserve the prior readable project | domain_data | Complexity guard before save/export and boundary regression requested |
| R13 | Backup publication must not replace a concurrently created destination | domain_data | Initial hardlink solution fails Android SELinux; exclusive-create copy/fsync correction requested |
| R14 | Back during recording preparation must not start an unwanted take | app_ui | Preparation cancellation/navigation correction requested |
| R15 | Stale asynchronous export receipts must not overwrite a newer job; notification failure must not discard a completed video | app_ui | Race and success-path audit requested |
| R16 | Picker result must retain its target project across process death | app_ui | Persist explicit import target, then restore project/editor after import |
| R17 | ZIP directory entries must not bypass expanded-size limits through unbounded closeEntry draining | domain_data | Independent audit reproduced 256 MiB hidden in a compressed Sources/ directory entry; reject payload requested |

## Android output publication decision

The API36 emulator rejects `Files.createLink` in app storage with a SELinux `{ link }` denial. JVM hardlink success is insufficient evidence for Android. Export/backup publication therefore uses exclusive destination creation (`CREATE_NEW` / `O_EXCL`), copies the already completed temporary output, and syncs the owned file before reporting success. Existing destinations are never opened for replacement. Cancellation/error cleanup must verify ownership before deletion. A process kill during this last copy can leave an unreported private file; it is not a completed export, gallery item or shared URI. Project manifest replacement still uses atomic rename and retained revisions.

## Verification observed so far

- Gradle 8.13 wrapper runs with JDK17.
- `:domain:compileKotlin` passes.
- `:media:compileDebugKotlin :domain:test` passes at intermediate code; nine domain tests passed.
- Three initial output-publication tests passed; media timeline test fixtures initially violated UUID validation and are being corrected.
- Dedicated API36 arm64 emulator `creator-android-api36`, serial `emulator-5580`, booted with emulated cameras. Other emulators/user data are excluded from tests.

These are intermediate checks, not final acceptance. The integrated APK and final code still require build, UI, media export, recovery and regression checks.
