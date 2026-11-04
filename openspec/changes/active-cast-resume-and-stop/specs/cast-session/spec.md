# Spec Delta

## Purpose

Defines how the receiver tracks an active cast, returns the viewer to the
active player page, and ends the current cast without stopping the receiver
service.

## ADDED Requirements

### Requirement: Active cast mode is tracked

The receiver SHALL expose exactly one active cast mode: none, video, mirror, or
audio. The mode SHALL be updated when a cast starts, when the sender stops or
tears down the cast, and when the viewer ends the cast. When a new cast starts
while another is active, the most recently started cast SHALL become the active
session.

#### Scenario: Video cast starts

- **WHEN** the receiver accepts a video cast
- **THEN** the active mode is video

#### Scenario: Mirroring starts

- **WHEN** the receiver accepts an AirPlay mirroring session
- **THEN** the active mode is mirror

#### Scenario: Audio cast starts

- **WHEN** the receiver accepts an AirPlay audio session
- **THEN** the active mode is audio

#### Scenario: Newer cast replaces the active mode

- **WHEN** a mirroring session starts while a video cast is active
- **THEN** the active mode becomes mirror

### Requirement: Opening the app returns to the active player

While a cast is active, launching the receiver from the TV launcher SHALL bring
the corresponding player page to the foreground. The main page SHALL NOT
replace or clear an active player page.

#### Scenario: Video player is resumed from the launcher

- **WHEN** a video cast is active and the viewer opens the app from the launcher
- **THEN** the video player page is shown in the foreground

#### Scenario: Mirror player is resumed from the launcher

- **WHEN** a mirroring session is active and the viewer opens the app from the
  launcher
- **THEN** the mirror page is shown in the foreground

#### Scenario: Audio player is resumed from the launcher

- **WHEN** an audio cast is active and the viewer opens the app from the launcher
- **THEN** the audio player page is shown in the foreground

#### Scenario: No active cast

- **WHEN** no cast is active and the viewer opens the app from the launcher
- **THEN** the main page is shown

### Requirement: Missing player pages are recovered when possible

If the active video player page is gone but its URL and title are known, the
receiver SHALL recreate the video player page. If audio is still playing, the
receiver SHALL recreate the audio page from the current audio session. If a
mirror page or mirror session is no longer alive, the receiver SHALL report
that mirroring has ended and SHALL NOT show a stale mirror page.

#### Scenario: Video page is recreated

- **WHEN** the video player page is gone and the active video URL is still known
- **THEN** the receiver opens a video player for that URL

#### Scenario: Audio page is recreated

- **WHEN** the audio player page is gone and audio is still playing
- **THEN** the receiver opens the audio page using the current audio metadata

#### Scenario: Mirror page cannot be recovered

- **WHEN** the mirror page and mirror session are gone
- **THEN** the receiver reports that mirroring has ended and does not show a
  stale mirror page

### Requirement: Main page exposes a return-to-player action

While a cast is active, the main page SHALL show a visible active-cast summary
with the cast mode and any available title or track information, plus an action
that returns to the active player. The summary SHALL disappear when the cast
ends.

#### Scenario: Active cast summary is shown

- **WHEN** the main page is visible while a cast is active
- **THEN** it shows the cast mode, available title or track, and a return action

#### Scenario: Return action opens the player

- **WHEN** the viewer selects the return action
- **THEN** the active player page is brought to the foreground

#### Scenario: Summary is removed after the cast ends

- **WHEN** the active cast ends
- **THEN** the active-cast summary is no longer shown on the main page

### Requirement: Viewer can end the active cast from the player

Each player page SHALL expose an end-cast action that is reachable with the TV
remote. Invoking the action SHALL stop the current playback or mirroring, close
the active protocol session, clear the active mode, and return the viewer to
the main page. The action SHALL require a deliberate selection so that an
accidental Back press does not end the cast.

#### Scenario: Back opens the player control panel

- **WHEN** the viewer presses Back on a player page
- **THEN** a control panel appears with a continue action and an end-cast action

#### Scenario: Continue keeps the cast running

- **WHEN** the viewer selects continue
- **THEN** the control panel closes and playback or mirroring continues

#### Scenario: End a video cast

- **WHEN** the viewer selects end cast on the video player page
- **THEN** video playback stops and the main page is shown

#### Scenario: End a mirroring session

- **WHEN** the viewer selects end cast on the mirror page
- **THEN** mirroring stops, the AirPlay connection is closed, and the main page
  is shown

#### Scenario: End an audio cast

- **WHEN** the viewer selects end cast on the audio player page
- **THEN** audio playback stops, the AirPlay connection is closed, and the main
  page is shown

### Requirement: Ending a cast keeps the receiver available

Ending the active cast SHALL NOT stop the receiver service or its DLNA and
AirPlay discovery. The device SHALL remain available for a new cast immediately
after the active session ends.

#### Scenario: Device remains discoverable

- **WHEN** the viewer ends the active cast
- **THEN** the device is still advertised for DLNA and AirPlay

#### Scenario: A new cast is accepted immediately

- **WHEN** a sender starts a new cast after the previous cast was ended
- **THEN** the receiver accepts it without restarting the app

### Requirement: Sender stop and teardown clear the active session

When the sender stops or tears down a cast, the receiver SHALL close the
corresponding player page and clear the active mode without requiring viewer
action.

#### Scenario: Sender stops a video cast

- **WHEN** the sender sends a DLNA Stop command
- **THEN** the video player closes and the active mode becomes none

#### Scenario: Sender tears down an AirPlay session

- **WHEN** the sender sends an AirPlay TEARDOWN
- **THEN** the mirror or audio player closes and the active mode becomes none
