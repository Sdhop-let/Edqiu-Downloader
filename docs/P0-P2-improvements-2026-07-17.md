# P0-P2 Improvements - 2026-07-17

## P0

- Restored the rule-strategy test surface with `DownloadRuleContext`, `DownloadRuleStrategy`, and `decideDownloadRule`.
- `DownloadViewModel` now uses the shared rule strategy instead of a private, untestable copy of the logic.
- Cancelling a task now propagates to yt-dlp via the task id as process id.
- Direct HTTP downloads now observe a cancellation predicate and remove partial output files when cancelled.

## P1

- Download tasks are persisted in Room through `download_tasks`, so completed, failed, and interrupted task state survives process restart.
- Active tasks restored after a cold start are marked failed with an interruption message rather than pretending to still download.
- Twitter cookies and WebDAV credentials use encrypted shared preferences with a safe fallback.
- Settings no longer prefill sensitive token/password values into editable text fields.
- WebDAV sync follows the configured download directory and uploads `.meta.json` sidecar files with media files.

## P2

- Resource storage now supports search by title, uploader, URL, and quality.
- Resource storage now supports sorting by newest, oldest, and largest.
- Player progress is remembered per local file and restored on replay.

## Follow-Up Candidates

- Add a true background download worker for resumable process-level recovery.
- Add WebDAV remote metadata tracking with ETag or modified time.
- Add grouped views by tweet id/uploader in the resource library.
