# Tasks

## 1. Player task isolation

- [x] 1.1 Add a dedicated `taskAffinity` to `PlayerActivity`, `MirrorActivity`, and `AudioPlayerActivity` in `AndroidManifest.xml`; verify `gradle :app:assembleDebug` succeeds.
- [x] 1.2 After starting each cast type, verify `adb shell dumpsys activity activities` shows the player activity in a separate task from `MainActivity`.
- [x] 1.3 Open the app from the launcher while a player page is alive and verify the player is not destroyed and playback continues.
- [x] 1.4 Verify Back from a player page no longer falls through to the TV home screen after the task split.

## 2. Active cast registry

- [x] 2.1 Add a `CastMode` enum and volatile mode field to `CastService`; set it in `onPlayVideo`, `onMirrorStart`, and `onAudioStart`; verify logcat reports `VIDEO`, `MIRROR`, and `AUDIO` for the three cast types.
- [x] 2.2 Clear the mode in `onStop`, `onMirrorStop`, and `onAudioStop`; verify a sender Stop or TEARDOWN returns the mode to `NONE`.
- [x] 2.3 Store the current video URL and title and expose a session snapshot for `MainActivity`; verify a video cast reports the saved URL and `VIDEO`.
- [x] 2.4 Add the `stopActiveCast(Activity caller)` skeleton that stops active protocol/playback state, leaves the caller for its own finish, and clears the mode without stopping the service; verify `isDlnaRunning()` and `isAirplayRunning()` remain true afterward.
- [x] 2.5 Verify a newer cast replaces the active mode and an older page's `onDestroy` does not clear the new mode.

## 3. Resume routing and main page

- [x] 3.1 Implement `CastService.openActivePlayer()` to bring an alive player page forward with `NEW_TASK | SINGLE_TOP`; verify video, mirror, and audio each return to their page after Home then launcher.
- [x] 3.2 Add video recreation from the saved URL/title; verify a video page that is gone is recreated and starts playback.
- [x] 3.3 Add audio recreation from `AudioSession`; verify a missing audio page is recreated while audio keeps playing.
- [x] 3.4 Add mirror-ended reporting when the page and stream are gone; verify the app shows an ended message instead of a stale mirror page.
- [x] 3.5 Add the active-cast summary and `返回播放器` action to `MainActivity`; verify the card appears for each mode, opens the player, and disappears after the cast ends.
- [x] 3.6 Wire `MainActivity.onCreate` and `onNewIntent` to the routing method; verify the launcher path does not create a routing loop and does not clear the player.
- [x] 3.7 Update `README.md` with the new resume behavior and main-page entry; verify the documented steps match the manual flow.

## 4. Player end-cast panel

- [x] 4.1 Add a Back-key modal panel to `PlayerActivity` with focusable `继续观看` and `结束投屏` actions; verify D-pad focus works and existing seek/resize shortcuts still work when the panel is closed.
- [x] 4.2 Add the same panel to `MirrorActivity` and `AudioPlayerActivity`; verify it opens and dismisses on both pages.
- [x] 4.3 Wire `结束投屏` to `CastService.stopActiveCast(this)`, start `MainActivity` from the foreground player, and finish the player activity; verify video, mirror, and audio each return to the main page.
- [x] 4.4 Verify a single Back press only opens the panel and does not end the cast; verify ending requires selecting `结束投屏`.
- [x] 4.5 Update `README.md` with the Back panel and end-cast usage; verify the documented keys match the implementation.

## 5. AirPlay connection teardown

- [x] 5.1 Track accepted client sockets in `RtspListener` and add `closeActiveClients()`; verify closing a socket unblocks its handler and runs the existing per-connection cleanup.
- [x] 5.2 Call `closeActiveClients()` from `RtspListener.stop()` and expose `AirPlayServer.stopActiveSessions()`; verify service shutdown leaves no active mirror or audio threads.
- [x] 5.3 Wire `AirPlayServer.stopActiveSessions()` into `stopActiveCast()`; verify an iPhone mirror and an iPhone audio cast disconnect on end cast.
- [x] 5.4 Verify DLNA and AirPlay discovery remain active after an end-cast operation and a new cast is accepted without restarting the app.
- [x] 5.5 Verify sender-initiated AirPlay `TEARDOWN` still closes the player page and clears the mode.

## 6. Integration verification

- [x] 6.1 Build and install the debug APK on `192.168.2.14:5555`; verify the app starts and both receiver self-checks are green.
- [x] 6.2 Run the video matrix: cast, Home, reopen, resume, end, and verify the receiver stays discoverable.
- [ ] 6.3 Run the mirror matrix: cast, Home, reopen, resume, end, and verify the iPhone mirroring session disconnects.
- [ ] 6.4 Run the audio matrix: cast, Home, reopen, resume, end, and verify audio stops and the page returns to main.
- [x] 6.5 Verify sender Stop/TEARDOWN and a new cast immediately after end; confirm no stale player page or stale active-cast card remains.
- [x] 6.6 Run `openspec validate active-cast-resume-and-stop --strict` and verify it passes.
- [x] 6.7 Build the release APK and verify the artifact in `app/build/outputs/apk/release/`.

## Workflow follow-up

- Archive the change after implementation and all verification tasks pass.
