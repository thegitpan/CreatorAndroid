# Initial integration contract

This contract scopes the first integrated working build. PLAN.md remains the broader product plan. Start with all 0.1 features, then extend after the root audit. Source naming below is the agreement between the three writers; if a type needs adjustment, notify root and other consumers.

## Ownership

- Domain/data agent: `domain/`, `data/`; pure Kotlin/JVM modules with serialization and JUnit. Filesystem repository takes java.io.File, never Android Context. It is callable from Dispatchers.IO in app.
- Media agent: `media/`; Android library with CameraX, Media3, metadata/thumbnail/waveform and capabilities. Also its tests. No app UI or root Gradle.
- App agent: root Gradle configuration/wrapper, `app/`, CI and build scripts. Composes domain/data/media APIs. Can add dependency/build files for other modules initially, with coordination.
- Root: docs, provenance, environment setup, review, integration, commits and delivery.

## Scope and technology

App identity `it.panebarco.creator`, visible name `Creator`; initial version 0.1.0, recorder redesign 0.1.1/code 2. Distinct original minimal vector icon. Italian dark UI with Projects, Recorder, Editor and diagnostics/settings. minSdk 31, compile/target SDK 36 initially (SDK37 is available if dependencies require it). JDK17 is installed at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; SDK at `/opt/homebrew/share/android-commandlinetools`. Local paths only in ignored local.properties/scripts auto-discovery, not portable config.

Use Kotlin serialization for domain data; kotlinx.coroutines for app/media asynchronous work. Prefer compatible stable AGP/Kotlin/Compose versions and verify real artifact metadata. CameraX baseline1.6.2 with selectable Gradle property to build1.7.0-rc02 diagnostics; Media3 1.11.1 candidate. If using AGP9 built-in Kotlin, follow its actual plugin contract; minimize build complexity. A fresh checkout must build via Gradle Wrapper.

## Domain/data public API to provide

Package `it.panebarco.creator.domain`:

- `enum class CanvasShape { PORTRAIT, LANDSCAPE }` (9:16,16:9).
- `@Serializable data class SourceAsset(id: String, fileName: String, durationUs: Long, width: Int, height: Int, rotationDegrees: Int = 0, hasAudio: Boolean = true, sha256: String)`.
- `@Serializable data class TimelineClip(id: String, sourceId: String, sourceStartUs: Long, sourceEndUs: Long, volume: Float = 1f)` with `durationUs`.
- `@Serializable data class EditSnapshot(clips: List<TimelineClip>, canvas: CanvasShape, captureCursor: Int)`.
- `@Serializable data class CaptureEntry(clip: TimelineClip)`.
- `@Serializable data class Project` with `id`, `title`, `createdAtMs`, `updatedAtMs`, `sources`, `history`, `historyCursor`, `captureLedger`, `schemaVersion`, `revisionId`, `ancestorRevisionIds`; computed `clips`, `canvas`, `durationUs`, `canUndo`, `canRedo`. Compute current materialized state by applying capture ledger beyond snapshot cursor. Public companion `create(title: String, canvas: CanvasShape = PORTRAIT): Project`.
- `object ProjectEditor`: functions return new Project: `appendRecording(project, source)`, `appendImport(project, source)`, `split(project, clipId, sourceTimeUs)`, `trim(project, clipId, startUs, endUs)`, `delete(project, clipId)`, `move(project, clipId, targetIndex)`, `setVolume(project, clipId, volume)`, `setCanvas(project, canvas)`, `rename(project, title)`, `undo(project)`, `redo(project)`, `validate(project)`.
- `appendImport` adds an undoable clip and preserves its original source. `appendRecording` adds durable ledger entry without clearing Redo. Split time uses source coordinates, while UI playhead uses cumulative timeline time.
- Volume is finite 0..4. `move` uses the final zero-based target index. `rename` changes library metadata outside timeline Undo/Redo. `TimelineTime` supplies cumulative/source coordinate mapping and rational frame boundaries.
- Each actual edit/metadata/capture operation generates a fresh revision UUID and appends its parent to persisted ancestry. Repository saves must descend from the current revision, including batches of operations; wall-clock timestamps alone cannot establish that an edit is current. Legacy schema-1 manifests without a revision ID receive a deterministic identity on read.
- First iteration uses Long microseconds directly aligned with Media3, with explicit bounds and cumulative timestamp conversions. This is a documented departure from upstream 240k ticks; source timestamp boundaries stay explicit and tests must cover rounding/no accumulated drift. Do not introduce Float seconds into persistence.

Package `it.panebarco.creator.data`:

- `class ProjectRepository(root: File)`; synchronous serialized/locked I/O, caller dispatches off-main. Methods `listProjects(includeTrash: Boolean = false): List<Project>`, `create(title, canvas = PORTRAIT): Project`, `load(id): Project`, `save(project)`, `sourceFile(projectId, sourceId): File`, `projectDirectory(id): File`, `moveToTrash(id)`, `restore(id)`, `exportArchive(id, destination: File)`, `importArchive(archive: File): Project`.
- `listProjects(true)` includes active and trashed projects. `isInTrash(id): Boolean` exposes the distinction without inferring directory paths in UI.
- Need safe import/capture staging with public `stageFile(projectId, extension = "mp4", recording: Boolean? = null): File` and `commitSource(projectId, staged: File, metadata: SourceAsset, recording: Boolean): Project`. Metadata fileName is replaced by repository-controlled name, SHA computed/verified by repository. Media agent metadata probe can provide placeholder id/hash; repository returns final asset in saved Project.
- `stageFile` lives under project pending directory. On commit, validate source metadata and persist a source-commit receipt before moving to Sources. Receipts replay an interrupted commit exactly once on load. `pendingMediaFiles(projectId): List<File>` exposes nonempty UUID-named media still awaiting a valid probe and explicit recovery. Source cleanup explicit, never automatic deletion of originals.
- Staging persists the operation origin before exposing the media file: `recording=true` for camera, `false` for imports. `pendingMediaOrigin(projectId, file): Boolean?` retains the distinction after restart. Unknown/invalid legacy origin returns null with a warning and is recovered as an undoable import; never silently promote it to a durable capture. Recovery probes a decodable frame before reporting a recovered video.
- Project IDs and filenames are generated UUIDs/safe relative paths. Untrusted IDs/archives must not escape root. Atomic snapshot + retained manifest revisions, no silent empty-project fallback. ZIP restore verifies all sources, including Redo references; duplicate import gets new project ID.
- `recoveryWarnings(): List<RepositoryWarning>` where `RepositoryWarning(projectId: String, message: String)` reports persistent recovery/corruption diagnostics. `load` may return a validated recovered revision after preserving the damaged manifest; UI shows the warning. `listProjects` isolates corrupt projects and records warnings rather than hiding failures or failing the whole library. Total corruption throws IOException on explicit load. `ProjectEditor.validate` returns Unit or throws IllegalArgumentException.
- A valid manifest with missing source files retains its exact timeline and history with a warning. Warning-storage failure falls back to an in-memory warning. Retain five validated complete-history manifest revisions; original media and damaged manifests remain preserved. Read and write limits must agree so a successful save is always readable.

## Media API to provide

Package `it.panebarco.creator.media`:

- `object MediaProbe { fun inspect(file: File): SourceAsset }` (duration, dimensions, rotation, audio) and helper thumbnails/waveform if needed.
- `class CameraSession(context: Context)` with state `StateFlow<CameraUiState>` and `bind(owner: LifecycleOwner, previewView: PreviewView, front: Boolean)`, `start(output: File, audioEnabled: Boolean, onFinalized: (Result<File>) -> Unit)`, `stop()`, `switchCamera()`, `setZoom(ratio: Float)`, `focus(x: Float, y: Float)`, `close()`.
- Optional focus/exposure controls use `setFocusLocked(Boolean)` and `setExposureLocked(Boolean)` with separate capability, pending and observed-lock state. Camera2 capture results confirm the actual lock; unsupported combinations are not advertised.
- CameraUiState exposes ready/starting/recording/finalizing/error/durationUs/zoom bounds/front and capability flags as appropriate; communicate exact shape to app agent early. REC begins on CameraX's Start event. Camera start/switch calls main thread. Pause UI means stop->finalize->commit; Resume starts new segment. Camera switching is available between segments; switching during a segment is not exposed in 0.1. Interrupted Finalize codes that may contain usable media require metadata/frame validation and a visible warning before committing the recovered take.
- `class ProjectMediaEngine(context: Context)` with `fun createPreview(project: Project, resolve: (String) -> File): androidx.media3.common.Player`, `suspend fun export(project: Project, resolve: (String) -> File, output: File, onProgress: (Int) -> Unit)` cancellable and leaves only valid completed output. `resolve(sourceId)` returns the file.
- Preview/export map the same timeline. Output 1080 canvas H264/AAC SDR with portrait/landscape fit. Volume per clip, silent clips, non-keyframe trims, and mixed rotation must match. All Android media/player thread requirements honored. Caller releases returned player. If API mapping needs another shape, message app agent immediately.
- Android SELinux rejects hardlink publication in app storage. Completed exports and backup ZIPs therefore use exclusive destination creation, copy, fsync and close before success is reported. Existing output is not replaced; failure cleanup verifies ownership. A process kill during this final private copy may leave an unreported file, never a completed receipt/gallery publication.
- Ownership checks require a live file descriptor until the commit/rollback decision: Linux can recycle inode identities after the last handle closes. Android explicitly closes both the non-owning FileOutputStream wrapper and its original Os.open descriptor, retaining a separate Os.dup lease. A closed publication can never delete by its historical identity. Backup publication keeps a verified READ handle while closing the writer and fails conservatively when ownership cannot be verified. Preserve primary errors and close every handle. These publishers operate in app-controlled private storage; an identity check followed by unlink is not an atomic conditional unlink against an arbitrary external path mutator.
- `object DeviceDiagnostics { fun collect(context: Context): String }` JSON technical capability report, no serial/unique device IDs/media uploads. All unavailable hardware is reported, not assumed.

## UI and integration acceptance

Project list create/rename/trash/restore/import archive; record into new or existing project; import videos through picker and copy into staging off-main; editor selectable clips/trim/split/delete/reorder/volume/canvas, persistent Undo/Redo, preview/playhead, thumbnails/waveform. Export foreground service with notification/cancel, Save to gallery and share FileProvider; archive via SAF. Cancellation and process interruption leave saved project usable. Actual controls tested with UI automation/instrumentation. Debug-only fixture import/seeding is allowed only under a separate explicit development path, no exported release backdoor.

Recording/camera entry and heavyweight exports are mutually exclusive. Reserve an export synchronously before starting its asynchronous service, and release camera ownership only after finalization. Split targets the clip under the playhead independently of an older selection. Undo/Redo remain visible when the capture/import action row would otherwise overflow. Low-level provider/codec/filesystem messages are translated to Italian operation-specific guidance at the app boundary.

A process-wide recording reservation starts before staging and remains held through CameraX finalization and the repository commit. A recreated Activity waits before binding its new camera or offering REC; export also waits for this reservation. Cancellation before coroutine dispatch and after handoff both release the exact token through completion handlers. Gesture callbacks read live camera/model state, so stale rendered buttons cannot stage a second recording during finalization. Preserve the chosen camera across Activity recreation. Rotating during REC can finalize the current segment; never restart automatically, duplicate the segment, reorder captures or silently switch to the other lens.

The user's 0.1.1 redesign supersedes the initial separated preview/control layout: see RECORDER-UX.md. The recorder is a full-screen camera with all controls overlaid on the video, compact icons/labels, and an always-visible circular REC/Pause button. No scrolling camera page, external tools column or project title bar. Preserve the complete frame through FIT_CENTER, with only aspect-ratio bands where necessary, and keep overlay touch targets accessible at larger text sizes and in landscape. Overlay panels never resize the preview. Emulator layout/lifecycle checks do not replace physical front/rear framing acceptance after the last relevant change.

No stub success callbacks or hardcoded completed/progress data. Missing implementations are surfaced as pending to root, never declared complete. Build, tests and error reproduction evidence are part of delivery.
