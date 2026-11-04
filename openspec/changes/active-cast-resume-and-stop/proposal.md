# Proposal

## Why

While a cast is active, pressing the TV Home button and later reopening the app
lands on the main page. Audio keeps playing because it is owned by the receiver
service, but video and mirroring stop because their activities were cleared, so
the user must cast again. There is also no way to deliberately end an active
cast session from the player page.

## What Changes

- Add an active cast session registry in `CastService` that records the current
  mode (`VIDEO`, `MIRROR`, or `AUDIO`) and the state needed to reopen the player.
- Put the player activities in a dedicated task so launching `MainActivity` no
  longer clears them. Opening the app while casting returns to the active
  player; `MainActivity` also shows a "return to player" entry as a fallback.
- Add a player control panel on the Back key with "continue watching" and
  "end cast" actions.
- Add `CastService.stopActiveCast()` to stop the current player page and active
  AirPlay connections, clear session state, and return to the main page while
  keeping the DLNA and AirPlay receiver service running and discoverable.
- Recover video from saved URL/title and audio from `AudioSession` when the
  player page is gone. Mirroring is recoverable only while its page/session is
  still alive; otherwise the app reports that mirroring has ended.
- Preserve existing sender-initiated Stop, Pause, Resume, Seek, and metadata
  behavior.

## Capabilities

### New Capabilities

- `cast-session`: tracks the active cast mode and session state, routes the user
  back to the active player, and ends the active session without stopping the
  receiver service.

### Modified Capabilities

None.

## Impact

- `app/src/main/AndroidManifest.xml`: dedicated task affinity for player pages.
- `MainActivity`: active-cast card and launcher routing.
- `CastService`: active session registry, player routing, and `stopActiveCast()`.
- `PlayerActivity`, `MirrorActivity`, `AudioPlayerActivity`: control panel and
  session-aware lifecycle.
- `AirPlayServer` and `RtspListener`: track active client connections and close
  them on forced stop.
- `AudioSession` and `MirrorSession`: cleanup and state reporting.
- No new dependencies. DLNA is push-only, so the sender's casting UI may not
  close immediately after a forced stop.
