# XInvox / Es Qp change summary

Date summarized: 2026-07-20

This document records the product decisions, implementation changes, fixes, release work, and verification work made around XInvox / Es Qp. It is intended as a maintenance handoff so future work can understand why the app is shaped as a combined XInvox inbox and TwitterDownloader-style downloader.

## 1. Product direction

XInvox was changed from a standalone X/Twitter link inbox into a merged Es Qp experience that combines:

- XInvox inbox: capture, organize, search, filter, batch manage, and dispatch X/Twitter status links.
- Downloader: parse links, download media, manage download tasks, scan local files, provide a media library, and play downloaded media.
- Shared navigation: bottom-tab based app structure where inbox, download, media library, and personal/settings areas work as one product.
- Market-oriented UI: multiple redesign rounds were requested and checked through real device screenshots rather than relying only on static designs.
- Compatibility and release strategy: package names, authorities, release metadata, update JSON, APK assets, and GitHub release structure were adjusted as part of the app transition.

The key product rule during the redesign was: preserve the existing downloader feature set while bringing XInvox into the same app shell and making the inbox useful as the entry point for later downloading.

## 2. Package, identity, and release changes

Package and identity work was part of the user's planned operation, not an accidental inconsistency. The project contains several related identifiers because XInvox, Es Qp, and the downloader integration had to coexist:

- Kotlin namespace: `com.ed.xinvox`.
- Release package metadata used for XInvox / Es Qp release notes.
- Downloader-facing application/package contracts for launching and media sharing.
- Provider authorities used by XInvox to read downloaded media and sidecar metadata from the downloader.
- Legacy authority support so older downloader naming can still be queried where needed.

Important package / authority concepts used by the implementation:

- XInvox namespace: `com.ed.xinvox`.
- Downloader package launched by XInvox: `com.ed.twitterdownloader`.
- Downloader action: `com.ed.twitterdownloader.action.DOWNLOAD_TWEET`.
- Downloader extra: `com.ed.twitterdownloader.extra.TWEET_URL`.
- New downloader media authority: `com.ed.twitterdownload.media`.
- Legacy downloader media authority: `com.ed.twitterdownloader.media`.
- FileProvider authority uses the active app package plus `.fileprovider`.

Release metadata was also created or updated for XInvox v1.2.0:

- App name: XInvox / Es Qp.
- Version name: `1.2.0`.
- Version code: `3`.
- Versioned APK: `xinvox-v1.2.0.apk`.
- Latest APK alias: `xinvox-latest.apk`.
- Published date recorded in release metadata: `2026-07-18`.
- Release SHA256 recorded for published APK assets: `2ED9F90609DAF02180518B9F54B35EA5ABA8E26ECCCC29E40754375EC12A49DF`.

## 3. Repository and release asset work

The release repository work added and updated public-facing release files:

- Added a v1.2.0 release entry for XInvox.
- Added `latest.json` describing the latest XInvox release.
- Added a versioned APK asset for v1.2.0.
- Added a latest APK alias for app update / download flows.
- Added upgrade notes describing version, install path, verification, and SHA256.
- Updated the v1.2.0 asset after the first release commit so the final APK and hash matched the intended build.
- Updated documentation so users could identify the latest XInvox package and install it directly.

The release history contained two relevant commits:

- Initial addition of XInvox v1.2.0 release assets and docs.
- Follow-up update replacing / correcting the XInvox v1.2.0 release asset and matching metadata.

## 4. UI and navigation redesign

The UI went through several product-driven redesign stages:

- Initial XInvox UI review and redesign planning.
- A professional inbox-style redesign focused on video cards, status filters, and practical batch actions.
- Figma-inspired redesign iterations.
- Real-device ADB screenshot review after design changes.
- Full app UI audit including bottom tabs, child screens, player screen, media library, downloader pages, and settings.
- A later round focused specifically on media library cards, media thumbnails, sync states, and player usability.

Main UI changes:

- Main list became a video inbox style feed rather than a plain text list.
- Link cards now emphasize thumbnails, author avatar, author name / handle, caption preview, status, relative time, and short URL context.
- Detail page became more media-forward, with a larger preview area, author metadata, original link, record information, and bottom actions.
- Filters were kept but restyled to be easier to scan.
- Search, sort, filters, selection mode, long press, batch actions, paste action, empty states, and loading states were preserved.
- Settings / Mine page was redesigned with card sections and clearer controls.
- Splash flow was added / refined for the Es Qp identity.
- Visual checks were done with screenshots and UI hierarchy dumps rather than only compile checks.

Bottom tab and module-level changes:

- XInvox inbox was integrated into the tab structure.
- Download functionality remained a core module.
- Media library became a major module instead of a hidden secondary feature.
- Mine / settings area collected paths, sync, backup, and app version information.
- Player remained a full media experience while being reachable from the media library.

## 5. X/Twitter link capture changes

The capture workflow was expanded and repaired:

- Manual paste-and-capture flow was retained and fixed.
- Latest capture flow was checked and fixed after it failed to apply correctly.
- Clipboard recognition was added / enhanced so X/Twitter links copied by the user can be detected.
- Sharing from Twitter/X or other apps into Es Qp was supported through Android text sharing.
- Text processing actions were supported for selected text.
- Accessibility-assisted capture was preserved as an optional path.
- Links are normalized to canonical `https://x.com/i/status/{tweetId}` form.
- Tweet ID deduplication uses Room primary key and insert-ignore behavior.
- Duplicate links are handled as already captured rather than inserted again.
- Capture is designed not to lose the saved link if metadata fetching later fails.

Capture-related UX goals:

- User can paste a link and immediately save it to the inbox.
- User can share from Twitter/X to the app.
- User can batch manage links later.
- User can open the original Twitter/X status from the saved record.
- Downloaded links can be excluded from repeated downloads.

## 6. Metadata enrichment changes

Metadata enrichment was one of the main feature upgrades requested for XInvox:

- Fetch author display name.
- Fetch author handle / ID.
- Fetch author avatar.
- Fetch tweet / video caption.
- Fetch video thumbnail where available.
- Fetch image thumbnail for photo posts.
- Parse fxtwitter public API response.
- Parse downloader-generated `.meta.json` sidecar files.
- Merge remote metadata and local sidecar metadata into saved link records.
- Avoid overwriting existing local non-null metadata with remote null values.

Implementation details:

- `MetadataFetcher` handles fxtwitter response parsing.
- `TweetMeta` stores normalized app-internal metadata.
- Downloader sidecar parsing supports fields such as `tweetId`, `url`, `title`, `thumbnail`, `uploader`, and `authorName`.
- `SavedLinkDao.applyMeta()` uses `COALESCE` so null metadata does not clear known-good local fields.
- Metadata retry is run during background sync for records missing author, caption, avatar, or thumbnail.

Test coverage was added for:

- Parsing author avatar, handle, and caption from fxtwitter response.
- Parsing photo URLs as thumbnails.
- Building Twitter/X app and web fallback status URIs.

## 7. Download integration changes

XInvox was connected to downloader behavior in two ways:

- Internal download attempt through Es Qp / bundled downloader code.
- External downloader dispatch through explicit Android intent when needed.

Downloader dispatch behavior:

- Normalize the tweet URL before launch.
- Try internal media download first where available.
- If internal download does not produce files, launch the external downloader activity.
- Mark records as pending or downloaded depending on actual result.
- Track attempt count, last attempt time, last error, and next retry time.
- Batch download deduplicates tweet IDs before dispatch.
- Manual retry can reset exhausted failures.

Important fixes:

- Added a mutex around download request dispatch to reduce duplicate concurrent launches.
- Added background retry consumption through `DownloadSyncWorker`.
- Added retry policy with limited attempts and retry delay.
- Improved failure handling when downloader cannot be launched.
- Added direct downloaded-file result handling when internal downloader returns files immediately.

## 8. Download monitor and media scan changes

The download monitor became a central bridge between saved inbox links and real downloaded media files:

- Scan default internal download directory.
- Scan explicit file paths.
- Scan SAF / content URI trees.
- Query downloader media Provider.
- Support both new and legacy downloader media authorities.
- Read `relative_path`, display name, and last modified time from Provider results.
- Pair media files with sidecar `.meta.json` using complete relative paths.
- Extract tweet ID from sidecar metadata first, then fall back to filename extraction.
- Support videos and images.
- Store matched file path and downloaded time back into Room.

Important scan fix:

- `DownloadMonitor.scan()` now returns explicit `Success` or `Failure`.
- `SavedLinkRepository.refreshStatuses()` returns early on scan failure.
- This prevents a Provider / SAF / permission failure from being treated as a successful empty directory.
- That fix avoids incorrectly downgrading downloaded records back to pending when files still exist but scanning failed.

Supported media extensions include:

- `mp4`, `mkv`, `webm`, `mov`, `m4v`.
- `jpg`, `jpeg`, `png`, `webp`, `gif`.

## 9. Media library changes

The media library was redesigned and made more functional:

- Media library UI was optimized separately from the inbox.
- Media cards were improved for videos and images.
- Downloaded video / image count and storage size are shown.
- Empty state explains that downloaded and scanned media appear there.
- Manual sync / refresh control was added.
- Entering the media library triggers automatic refresh.
- Periodic media library refresh was added.
- Sync status pill was added so users can see when sync is running or complete.
- Media title, badge, metadata, and thumbnail handling were improved.
- Sharing and deleting media remained available.

Specific user-driven fixes:

- The media library sync button needed visible feedback.
- Local media scanning had to target Android-data downloader path.
- Downloaded media should appear in the media library after scan.
- Video thumbnails should be displayed, using downloader-provided thumbnails where possible.
- For multi-video links with the same thumbnail, later work discussed using different frames as thumbnails where feasible.

## 10. Player changes

The player screen received several rounds of fixes because functionality and landscape behavior regressed during UI redesign:

- Restored player functionality after it was lost.
- Reworked portrait layout.
- Reworked landscape / fullscreen behavior.
- Fixed confusion where app orientation changed but the video itself was not presented correctly.
- Reduced intrusive overlays in landscape mode.
- Made controls less likely to block the video.
- Added / refined bottom preview strip.
- Added current-playing highlight: the currently playing video receives a small visual ring / white circle marker.
- The bottom preview strip should move / scroll to the currently playing video.
- Added or preserved actions for copy, delete, open file, and open original Twitter/X status.
- Checked playback, fullscreen, landscape, back navigation, and restored states with ADB screenshots / UI hierarchy dumps.

User feedback that drove player changes:

- Player functions were missing after redesign.
- Landscape did not work correctly.
- Fullscreen icon did not behave correctly.
- The link icon in the middle top was unclear.
- Bottom preview strip was too large and needed a flatter, smaller design.
- Landscape mode was too obstructed by UI.

## 11. Settings, paths, and storage changes

Settings work focused on making both downloader paths and XInvox monitoring paths usable:

- Settings UI was redesigned and later aligned more closely with TwitterDownloader settings.
- Downloader custom path was exposed / repaired.
- XInvox download monitor directory became configurable.
- Manual monitor path input was added.
- Default path points to the downloader Android-data Download directory.
- Path normalization supports content URIs, file paths, and common external storage paths.
- Restore default monitor directory action was added.
- Backup directory selection was added.
- Manual backup path input was added.
- Automatic backup toggle was added.
- Last backup success and error status are shown.

Important storage goals:

- Users should be able to monitor `Android/data/com.ed.twitterdownload/files/Download`.
- Users should be able to configure paths manually when system pickers are restrictive.
- XInvox should be able to use Provider or SAF rather than relying only on raw filesystem access.

## 12. Backup, restore, and trash changes

Backup and restore were added as durable data-safety features:

- Added backup models and envelope format.
- Added payload checksum using SHA-256.
- Added validation for malformed or tampered backup files.
- Added active link export.
- Added deleted history export.
- Added preview counts before restore.
- Added merge restore mode.
- Added replace restore mode.
- Added pre-restore safety snapshots for replace restore.
- Added retention for generated automatic backups.
- Added automatic daily backup scheduling.
- Added backup Worker with retry behavior.
- Added UI controls for backup now, export, import, and automatic backup.

Trash / deleted history changes:

- Deleting active inbox records archives them into `deleted_link_history`.
- Single delete and batch delete both archive before deletion.
- Restore from trash is supported.
- Permanent deletion from deleted history is supported.
- Restore preserves metadata and download-related fields.
- Backup includes deleted history.

Fields preserved through backup / restore and trash restore:

- Tweet ID.
- Raw URL.
- Author ID and name.
- Caption.
- Thumbnail.
- Avatar.
- Saved time.
- Status.
- File path.
- Downloaded time.
- Attempt count.
- Last attempt time.
- Last error.
- Next retry time.

## 13. Room database changes

Room database evolved across multiple versions:

- Version 1: base saved links.
- Version 2: added download attempt and retry fields.
- Version 3: added deleted link history / trash table and indexes.
- Version 4: added avatar URL to active links and deleted history.

Important schema and migration changes:

- `attempt_count`.
- `last_attempt_at`.
- `last_error`.
- `next_retry_at`.
- `deleted_link_history` table.
- `index_deleted_link_history_tweet_id`.
- `index_deleted_link_history_deleted_at`.
- `avatar_url` on active links.
- `avatar_url` on deleted history.
- Exported Room schemas for versions 2, 3, and 4.

Related tests:

- SQL migration tests.
- Deleted link restore test.
- Backup link restore test.
- Metadata parsing tests.

## 14. TwitterDownloader feature carry-over

The work reused and carried forward earlier TwitterDownloader improvements so Es Qp would not lose downloader capability:

- Multi-video parsing.
- Media index tracking.
- Distinguishing quality-selection mode from multi-media-item mode.
- Download all detected media items.
- File naming that includes media index for multi-video links.
- yt-dlp playlist item handling.
- Direct downloader support.
- Sidecar `.meta.json` writing / reading.
- SAF-oriented export and storage logic.
- Dynamic version name usage.
- Room-backed download history.
- Media library scanning.
- Provider-based media exposure to companion apps.

User requirement behind this: the XInvox-integrated app should not be a one-line shell or reduced prototype; it must keep real downloader functionality.

## 15. Build and Gradle fixes

Several build issues were found and addressed during the project work:

- Kotlin / Compose compiler plugin alignment was required after Kotlin 2.0.
- KSP dependency resolution hit Maven / TLS issues during early builds.
- JVM target alignment was needed for Android / Kotlin builds.
- Room schema export path was configured.
- Debug APK generation was verified after fixes.
- Android test APK was also generated locally.

Known build artifacts observed during verification:

- Debug APK under `app/build/outputs/apk/debug/app-debug.apk`.
- Android test APK under `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

## 16. GitHub release and update metadata

Release metadata was created for app distribution:

- `latest.json` contains app name, package information, release name, APK path, version name, version code, publish date, versioned APK path, upgrade document path, and SHA256.
- `UPGRADE.md` describes install steps, normal overwrite-install behavior, device verification, tests, build source path, and SHA256.
- Both versioned and latest APK files point to the same published release build.

Important distinction:

- The published v1.2.0 APK hash recorded in release metadata is the release artifact hash.
- Later local debug APK builds can have different hashes and should not be confused with the published release asset.

## 17. ADB and real-device verification

Real-device verification was a major part of the workflow:

- APKs were installed or pushed with ADB.
- Screenshots were captured after UI changes.
- UI hierarchy XML files were dumped to inspect actual rendered content.
- Inbox, download, media library, player, settings, backup, image download, and landscape playback were all inspected through device artifacts.
- The work treated installation success as insufficient; the rendered UI and functional screens had to be checked.

Observed verification artifact categories:

- Inbox before / after screenshots.
- Detail page screenshots.
- Download page screenshots.
- Media library screenshots.
- Player portrait and landscape screenshots.
- Sync banner and toast screenshots.
- Mine / settings screenshots.
- Backup path and backup action screenshots.
- Device database snapshots.
- UI hierarchy XML dumps.

## 18. Major fixes by problem statement

These are the concrete user-reported problems and the corresponding change areas:

- Paste and capture failed: capture flow and UI action handling were repaired.
- Latest capture failed: latest-capture control was checked and repaired.
- Downloader functions were missing compared with v2.1.4: downloader functionality was merged back and preserved.
- Inbox needed feature parity with the earlier base APK: XInvox capture, list, detail, batch, status, and download flows were kept.
- UI looked poor: multiple redesign rounds were done, then checked through ADB.
- Bottom tabs and child pages needed full review: app-wide UI/navigation pass was performed.
- Player needed to match reference image: player preview strip, current-video highlight, and landscape behavior were revised.
- Custom paths could not enter Android-data: manual path and monitor path logic were added.
- XInvox monitor directory needed custom editing: settings support was added.
- Local media scan did not work: scan target and Provider / path handling were improved.
- Batch download left download page empty: downloader dispatch and media refresh paths were connected.
- Player functions were lost: player actions and layout were restored.
- Landscape / fullscreen was broken: player fullscreen and landscape behavior were revised.
- Media library sync button needed feedback: sync status message was added.
- Media blocks needed polish: media card UI and metadata presentation were improved.

## 19. Remaining caution areas

The following areas should be treated carefully in future development:

- Package naming and release metadata should stay intentionally documented, because namespace, application ID, downloader package, and Provider authority serve different compatibility purposes.
- If app identity changes again, update Manifest, Provider authorities, FileProvider authorities, release JSON, upgrade notes, and downloader contracts together.
- Manual Android-data paths can be device- and Android-version-sensitive; Provider / SAF should remain available as fallback paths.
- Media thumbnail extraction for multiple videos from the same tweet may need deeper frame extraction if downloader-provided thumbnails are identical.
- Background retry and scan behavior should keep distinguishing scan failure from successful empty results.
- Remote metadata should continue using null-safe merge rules.
- Backup restore should continue preserving download-related fields unless the user explicitly chooses a reset-style restore.
- UI changes should continue to be validated by actual screenshots, not just compilation.

## 20. Short final summary

XInvox / Es Qp changed from a simple X/Twitter link inbox into a combined inbox, downloader, media library, and player app. The work included package and release compatibility changes, X/Twitter metadata enrichment, Twitter/X app jump support, clipboard and share capture, batch download, Android-data media monitoring, Provider / sidecar scanning, media library sync, player redesign, backup / restore, Room migrations, GitHub release assets, and repeated ADB device verification.
