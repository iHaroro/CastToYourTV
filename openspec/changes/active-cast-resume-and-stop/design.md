# Design

## Context

See `proposal.md` for the motivation. The current implementation has three
constraints that shape this design:

- `MainActivity` is `singleTask` and shares the default task with all three
  player activities. Launching it from the TV launcher clears the player
  activities above it.
- Playback ownership is split. AirPlay audio is owned by the receiver service,
  while video (`ExoPlayer`) and mirroring (`MediaCodec`) are owned by their
  activities.
- `AirPlayServer.stop()` only stops discovery and the RTSP listener. Active
  mirror and audio sessions live inside individual `RtspListener` connection
  threads and are not tracked centrally.

## Goals / Non-Goals

**Goals:**

- Return the viewer to the active player page after using the TV Home button.
- Make the active cast visible and resumable from the main page.
- Provide a deliberate, remote-accessible way to end the active cast.
- Keep the receiver service and its DLNA/AirPlay discovery running after a
  viewer-initiated end.
- Preserve sender-initiated Stop, Pause, Resume, Seek, and metadata updates.

**Non-Goals:**

- No server-side session restoration after the Android process is killed.
- No attempt to keep a mirroring stream alive after its activity and
  `MirrorSession` are gone.
- No change to AirPlay, RAOP, or DLNA protocol compatibility.
- No shutdown of the receiver service from the player page.

## Decisions

### Player pages use a dedicated task affinity

All three player activities will move to a player-specific `taskAffinity`.
`MainActivity` keeps the default affinity and remains the launcher entry.

Why: this is the smallest change that prevents `MainActivity` from clearing a
live player page. Keeping the pages in the default task and recreating them
from saved state would lose mirroring, because `MirrorSession` and the codec are
owned by `MirrorActivity`.

Alternatives considered:

- A launcher router activity that forwards to the player before MainActivity.
  This avoids the clear but adds a second entry point and still needs a session
  registry for the main-page card.
- Recreate everything from state in `MainActivity`. Rejected for mirroring and
  for video playback position continuity.

Trade-off: the app now has two tasks. Back from the player should be handled by
the in-player control panel rather than falling through to the TV home screen.

### `CastService` owns a lightweight active-session registry

`CastService` will expose a volatile mode (`NONE`, `VIDEO`, `MIRROR`, `AUDIO`)
plus the minimum state needed to reopen a page:

- video: URL and title;
- audio: existing `AudioSession` metadata and playback flag;
- mirror: whether `MirrorActivity` and the mirror stream are alive.

The mode is set by the existing `CastSession.Callback` entry points:
`onPlayVideo`, `onMirrorStart`, and `onAudioStart`. It is cleared by
`onStop`, `onMirrorStop`, `onAudioStop`, and viewer-initiated
`stopActiveCast()`. Player activity destruction must not clear the mode, so a
new cast that replaces an old page cannot have its mode erased by the old
page's `onDestroy`.

The registry is process-local; it is not persisted. A process restart is a
receiver restart and requires a new cast.

### Routing is centralized in `CastService.openActivePlayer()`

`MainActivity` and the launcher entry call one routing method instead of
duplicating mode checks. The method:

1. brings an alive player page forward with
   `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`;
2. recreates video from the saved URL/title when the page is gone;
3. recreates audio from `AudioSession` when audio is still playing;
4. reports that mirroring ended when neither the page nor the stream is alive.

`MainActivity` calls this on launcher open and shows an active-cast card with a
"return to player" action. The card is the manual fallback when auto-routing is
not possible or when the viewer deliberately opens the main page.

### Ending a cast is a service operation

`CastService.stopActiveCast(Activity caller)` will:

1. stop the active protocol and playback state, and finish any other player
   page that is still registered;
2. ask `AirPlayServer` to close all active client connections;
3. stop `AudioSession` and `MirrorSession`;
4. clear the active mode and refresh the notification;
5. leave `DlnaRenderer`, `AirPlayServer`, discovery, and the foreground service
   running.

The caller is not finished by the service. The player activity that invoked
the action starts `MainActivity` and then finishes itself. This avoids Android
10 background-activity-start restrictions and gives a reliable return to the
app after the player task is emptied.

### AirPlay active connections are tracked

`RtspListener` will keep a concurrent set of accepted client sockets. Sockets
are added before the handler starts and removed in the handler's `finally`
block. `closeActiveClients()` closes each socket, which unblocks the handler and
runs the existing per-connection cleanup (`NtpTiming`, `MirrorReceiver`,
`AudioReceiver`, `AudioRenderer`, `MirrorSession`, `AudioSession`).

`RtspListener.stop()` will also close active clients so service shutdown no
longer leaves mirror or audio threads behind.

Why not stop and restart `AirPlayServer`: the listener's `ServerSocket` can be
closed, but existing client sockets stay open. Restarting would also race with
port 7000 rebinding.

### The player control panel is opened with Back

`PlayerActivity` currently consumes every direction key for seek and resize
mode. A permanently focusable corner button would conflict with those
shortcuts. Instead, Back opens a modal panel with focusable "continue" and
"end cast" actions. A modal window receives key events before the activity's
`onKeyDown`, so existing playback shortcuts remain unchanged while the panel is
closed. The same interaction is used on the mirror and audio pages.

Alternatives considered:

- A visible corner button: requires reworking the remote key map and makes the
  primary playback controls less predictable.
- A Menu-key shortcut: not consistently available on Sony TV remotes.

### DLNA has no upstream stop

DLNA is push-based. The receiver can release `ExoPlayer` and close the media
fetch, but it cannot send an AVTransport Stop back to the phone. The TV stops
immediately; the sender's casting UI may linger until the sender notices. This
is a protocol limitation, not a receiver failure. AirPlay sessions are closed
through their RTSP connection and normally disconnect immediately.

## Risks / Trade-offs

- **Two tasks may appear in recents** -> keep the player task out of recents if
  the TV launcher exposes them, and make the main page the explicit fallback.
- **Video restarts from the beginning when recreated** -> only recreate when
  the page is actually gone; keep the alive page in the player task so normal
  Home/reopen flows never recreate it.
- **A stale mode could survive a missed teardown** -> clear the mode on every
  sender Stop/TEARDOWN and on viewer end; add a service-side health check that
  clears video/mirror modes when their player page is gone and no stream is
  alive.
- **Closing AirPlay sockets may race with a new connection** -> close only the
  sockets registered at the time of the stop; a new SETUP accepted afterward
  creates a fresh session and becomes the active mode.
- **DLNA sender UI may not close** -> document the limitation and keep the
  receiver available so the viewer can start a new cast immediately.
- **Back no longer exits the player directly** -> the panel always offers
  continue and end cast, so the viewer retains an explicit exit path.

## Migration Plan

No data migration is required. Install the new APK over the existing release;
the receiver starts its service through the existing foreground-service path.
Rollback is reinstalling the previous APK. Existing senders continue to use the
same AirPlay and DLNA endpoints.

## Open Questions

None blocking implementation.
