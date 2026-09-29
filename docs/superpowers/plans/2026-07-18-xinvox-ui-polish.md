# XInvox UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish XInvox into a professional video inbox UI without losing existing functionality.

**Architecture:** Keep the current Compose screen structure and ViewModels. Limit changes to presentation components in list, card, detail, and theme files; no data or repository changes.

**Tech Stack:** Android Kotlin, Jetpack Compose, Material3, Coil.

---

### Task 1: Preserve Behavioral Coverage

**Files:**
- Test: `app/src/test/java/com/ed/xinvox/ui/list/LinkListOrganizerTest.kt`

- [ ] **Step 1: Run existing behavior tests**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.ed.xinvox.ui.list.LinkListOrganizerTest"`

Expected: PASS. This confirms search/sort behavior remains covered before visual edits.

### Task 2: Polish Link Cards

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/components/LinkCard.kt`

- [ ] **Step 1: Keep the existing public API**

Do not change `LinkCard` parameters. Preserve click, long-click, selection mode, selected state, and selection toggle behavior.

- [ ] **Step 2: Update card composition**

Use the current `SavedLink` fields and status color. Thumbnail remains `thumbnailUrl`; avatar remains `avatarUrl`; caption falls back to `rawUrl`; author falls back to `authorId` and then unknown author.

- [ ] **Step 3: Verify compile**

Run: `.\gradlew.bat assembleDebug`

Expected: PASS.

### Task 3: Polish List Header and Filters

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/list/ListScreen.kt`

- [ ] **Step 1: Keep all controls**

Retain header metrics, search, sort menu, filters, floating paste action, empty state, loading state, selection bar, and long-press dialog.

- [ ] **Step 2: Reduce visual weight**

Make the header more compact and make filter chips status-accented without changing their click behavior or counts.

- [ ] **Step 3: Verify compile**

Run: `.\gradlew.bat assembleDebug`

Expected: PASS.

### Task 4: Polish Detail Screen

**Files:**
- Modify: `app/src/main/java/com/ed/xinvox/ui/detail/DetailScreen.kt`

- [ ] **Step 1: Preserve bottom actions**

Keep download, copy, open X/Twitter, and open downloaded file actions. Do not change ViewModel calls.

- [ ] **Step 2: Improve hero card**

Use the existing thumbnail and avatar fields to make the hero card more media-forward, with status overlay and author metadata.

- [ ] **Step 3: Verify compile**

Run: `.\gradlew.bat assembleDebug`

Expected: PASS.

### Task 5: Final Verification

**Files:**
- No additional code files.

- [ ] **Step 1: Run unit tests**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Build APK**

Run: `.\gradlew.bat assembleDebug`

Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/debug/app-debug.apk` exists.
