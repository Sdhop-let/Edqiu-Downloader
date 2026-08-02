# XInvox UI Polish Design

## Goal

Refresh XInvox with a professional inbox-style interface while preserving every existing workflow: capture, search, sort, filter, batch selection, copy, download, delete, detail view, open downloaded file, and open the original X/Twitter status.

## Direction

Use the "Professional Inbox" direction as the base. Borrow richer video preview treatment from the gallery concept and tighter action density from the compact utility concept.

## Main List

- Keep the current functional layout: header metrics, search, sort, filters, list, floating paste action, selection bar, and long-press actions.
- Make the header lighter and more operational: show XInvox, queue summary, and three metrics without visually overpowering the list.
- Make filter chips easier to scan by keeping count labels visible and using status colors only as accents.
- Upgrade link cards to behave like video inbox items: video thumbnail on the left, author avatar overlaid on the thumbnail, author/title line, caption preview, status pill, status dot, relative time, and short status URL.

## Detail View

- Preserve all current actions: request download, copy link, open X/Twitter, and open downloaded file.
- Make the hero card feel more media-centric: large video preview, overlay status, author avatar/name, handle/status URL, and caption.
- Keep records and raw URL sections readable and copy-friendly.

## Constraints

- Do not remove or hide existing controls.
- Do not change persistence, metadata, download, or repository behavior.
- Avoid unrelated navigation changes.
- Keep implementation within existing Compose/Material3 patterns and dependencies.

## Verification

- Run `.\gradlew.bat testDebugUnitTest`.
- Run `.\gradlew.bat assembleDebug`.
- If installing, confirm `adb install -r -d app\build\outputs\apk\debug\app-debug.apk` succeeds.
