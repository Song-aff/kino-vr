# Changelog

All notable changes to KINO VR are listed here. Versions track the Android app
(`versionName`); the PC helper is versioned separately.

## 4.14 — 2026-11-20

- Playlist navigation retuned: `← →` step through entries one by one, `↑ ↓` jump a
  whole page (5 entries), confirm to play. Up/down no longer double as play/step.
- First release-signed build (R8 minified + resource shrinking).

## 4.13 — 2026-11-19

- Dual-eye vertical alignment is now selectable — **bottom** (default, suits headsets
  with a bottom support) or centered.
- Playlist browsing in the headset: directional keys move the highlight, only the
  confirm key starts playback. HAT / left-stick input supported on cheap gamepads.

## 4.12 — 2026-11-17

- VR menu becomes a control overlay on the video instead of a separate screen.
- Menu and Start keys split: Menu toggles the VR menu, Start opens the playlist.
- Gyroscope head tracking on by default.

## 4.11 — 2026-11-16

- Default viewing mode is 3D dual-lens (SBS).
- Playback controls freeze while the VR menu is up.

## 4.10 — 2026-11-16

- In-VR playback parameter panel, openable with a bindable gamepad shortcut.

## 4.9 — 2026-11-16

- Configurable gamepad key bindings for 16 actions with a capture-style editor.
- Unified navigation semantics across home / VR menu / playlist (`NavKeyRouter`).
- Readable labels for media and letter keys on cheap Bluetooth gamepads.

## 4.8 — 2026-11-15

- VR menu rendered on the big-screen plane; pairing dialog polish.

## 4.7 — 2026-11-15

- Home player button and solid amber selection states.

## 4.6 — 2026-11-15

- Rebrand to **KINO VR** with a black-and-amber theme and new icon.

## 4.5 — 2026-11-15

- Compact fullscreen home (Home / Player two-level navigation).

## 4.4 — 2026-11-13

- Big-screen orientation tri-state; auto-mute on the phone while PC streaming;
  in-app 4-digit PIN first-time pairing.

## PC helper (`pc-server`)

### v0.5 — 2026-11-16

- L2 capability proxy for Sunshine: PIN relay, local credential storage,
  version-aware input backend, session state.
