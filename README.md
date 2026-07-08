# TapChess ♞

A full game of chess against a computer opponent, built natively for the
**RayNeo X3 Pro** AR glasses. Move a cursor around the board with right-temple
**swipes**, **click** to pick up and place a piece — and when a move isn't
legal, TapChess **tells you why** instead of just refusing it.

Built from the FABLE_X3_STARTER_GUIDE recipe and the TapBubbles/TapMemory
lineage: 640×480 logical canvas, binocular side-by-side rendering, pure-black
waveguide-friendly background, zero vendor AARs, zero permissions, zero binary
assets — the whole chess engine, AI, and sound set are pure Kotlin, synthesized
at runtime.

## Controls (right temple pad)

| Gesture | Action |
|---|---|
| **Swipe ↑ ↓ ← →** | Move the cursor one square (menus: navigate) |
| **Click** (temple tap) | Select your piece · place it · confirm |
| **Double-tap** | Open / close **settings** any time |
| Left temple pad | System volume (ignored) |

Click your piece (it highlights with its legal destinations), swipe the cursor
to a target, and once the ring around it fills — **rest there about two
seconds** — click again to commit the move. That short dwell is a deliberate
guard: the X3 temple pad isn't a perfect pointing device, and requiring the
cursor to settle before a tap commits means a stray swipe can't fling a piece
onto the wrong square. Click the same square to deselect, or click another of
your pieces to switch. Also plays on a plain touchscreen with the same
gestures. If a swipe direction reads backwards on your hardware, flip it in
settings.

### Why a move was rejected

Every illegal attempt gets a one-line explanation at the bottom of the board —
for example:

- *"A bishop moves only diagonally."*
- *"That would leave your own king in check."*
- *"A pawn can't capture straight ahead."*
- *"You're in check — that move doesn't save your king."*
- *"The rook's path is blocked."*

It's a chess tutor as much as an opponent.

## Computer difficulty

Five tiers, selectable on the title screen (swipe) or in settings:

| Tier | Strength |
|---|---|
| **1 · Pawn** | Looks one move ahead and often blunders — a gentle start |
| **2 · Knight** | Two-move search, occasional slips |
| **3 · Bishop** | Three-move search, plays soundly |
| **4 · Rook** | Three moves + capture (quiescence) search, no mercy |
| **5 · Queen** | Four-move alpha-beta search with move ordering |

The engine uses alpha-beta search with piece-square evaluation, capped by a
per-move time budget so it always answers within a couple of seconds, running
on a background thread so the UI never stalls while it thinks.

## Speed mode

Turn on a chess clock in settings: **10:00 / 5:00 / 3:00 / 1:00** per side.
Each player's clock ticks on their turn, the readout flashes red under ten
seconds, and running out flags the game. Leave it **Off** for relaxed,
untimed play.

## Never lose your place

After the computer replies, the cursor jumps to your own king and its last
move draws in as a fading, animated dotted trail with an arrowhead — so you
always land oriented on where the action is. The game **auto-saves after every
move** (and when you background the app), and resumes exactly where you left
off the next time you launch it.

## Settings (double-tap)

Difficulty · play as White/Black · speed mode · show legal moves · show
coordinates · sound volume · swipe sensitivity · flip vertical/horizontal ·
safe tap · particles · frame cap (30/60 fps) · new game · undo move · resign ·
reset stats · **Reset Settings** (bottom of the menu, confirm-twice — restores
every preference to default without touching your win/loss record or an
in-progress game).

Menu navigation is **discrete**: one physical swipe always equals exactly one
step, classified the instant your finger lifts — no drift, no lag, no
accidentally skipping past the option you wanted.

Full rules are implemented: castling, en passant, promotion (with a picker),
check, checkmate, stalemate, insufficient-material and 50-move draws. Wins and
losses are tracked.

## Build & install

```bash
cd ~/Projects/TapChess
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A built `TapChess.apk` also ships in this repo's root for a quick sideload
without a build environment.

Toolchain: gradle 8.9 wrapper · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 ·
compileSdk 35 / minSdk 29.

## X3 specifics honored

- Black is transparency: the board floats as neon light on the world
- No `ar_mode` meta-data (it would halve the display to one lens)
- Temple click read as a KEY event (`KEYCODE_BUTTON_A`/`DPAD_CENTER`);
  swipes classified by net displacement on finger-up, one gesture = one step,
  in every context (board, menus, settings)
- `cyttsp6` (left arm volume pad) filtered out by device *name*
- RayNeo hardware detected by manufacturer/brand/product, not `Build.MODEL`
  (the X3 Pro reports `ARGF20`); binocular SBS auto-defaults on for it, no
  manual toggle needed
- Dwell-to-commit on moves; AI runs on a background thread; the sleep button
  auto-pauses into settings; the game persists after every move

## A note on the controls

The X3 Pro's temple pad isn't a perfect input device — a swipe can land as the
wrong direction or a stray click. Chess is turn-based and forgiving of that:
nothing commits until the cursor has rested on a legal square, illegal
placements are explained and harmless, and **Undo Move** is one double-tap
away. Raise **Swipe Sensitivity** if the cursor feels stubborn.
