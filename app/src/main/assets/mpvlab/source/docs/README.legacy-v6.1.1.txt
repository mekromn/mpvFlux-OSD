PIXEL 9 PRO XL — V3.1 SHADER LAB WORKSTATION v6.0
====================================================

STATE-SAFE UPGRADE
------------------
This package DOES NOT contain pixel9-shader-lab-state.txt and does not
contain any user-preset files. Copying it over /storage/emulated/0/mpv will
therefore not overwrite your existing saved A/B tuning.

Recommended upgrade:
  1. In the current Lab, SAVE STATE once.
  2. Copy this package over /storage/emulated/0/mpv.
  3. Start a video.
  4. Use SYSTEM -> Load complete Lab state (or Ctrl+L).

Your old A/B state format remains supported. New controls missing from an old
state simply keep their safe defaults.

NATIVE APK
----------
The existing v5.7.3 APK patch supports the entire v6 menu/workstation.
For the NEW physical long-hold gestures, apply the companion v5.8 APK Editor
patch to the same CLEAN APK baseline:

  center hold (500 ms) -> original while held; release restores tuning
  left hold            -> accelerated menu navigation left
  right hold           -> accelerated menu navigation right

When Shader Lab is hidden, mpvFlux's stock long-press behavior is untouched.
Value adjustment NEVER accelerates: long-hold repeat is browse/navigation only.

PHONE CONTROLS
--------------
BROWSE:
  LEFT          previous menu item
  CENTER        edit/select/action
  RIGHT         next menu item
  TOP           previous control group
  BOTTOM        next control group

EDIT:
  LEFT          one exact step down
  CENTER        finish editing
  RIGHT         one exact step up
  TOP           coarser step size
  BOTTOM        finer step size

LONG HOLD with v5.8:
  LEFT          accelerated previous-item navigation
  CENTER        hold-to-preview original
  RIGHT         accelerated next-item navigation

WORKSTATION FEATURES
--------------------
1. ONE-TOUCH BYPASS COMPARISON
   COMPARE -> One-touch bypass comparison.
   Bypasses Lab shader + Lab-controlled mpv properties without changing B.
   Toggle again to restore the exact tuning.

2. HOLD-TO-PREVIEW ORIGINAL
   Center long-hold with the v5.8 APK patch. Original appears after the normal
   500 ms long-press threshold and remains only while the finger is held.
   Release restores the current tuning.

3. 10 USER PRESET SLOTS
   PRESETS -> User preset slot selects USER 01..10.
   Separate Load / Save / Clear actions are provided.
   Save, overwrite, clear, load and other destructive operations use OSD
   double-confirmation where appropriate.

   User files live beside the normal Lab state:
     /storage/emulated/0/mpv/state/pixel9-user-preset-01.txt
     ...
     /storage/emulated/0/mpv/state/pixel9-user-preset-10.txt

4. 10 READ-ONLY BUILT-IN PRESETS
   B01 V3.1 Reference
   B02 Natural Plus
   B03 Vivid Clean
   B04 Cinema
   B05 Daylight Punch
   B06 Dark Room
   B07 Animation
   B08 Skin Priority
   B09 Highlight Pop
   B10 SDR Safe

   These are starting points; they never overwrite the 10 user slots.

5. PRESET MORPHING
   MORPH -> Morph from preset
   MORPH -> Morph to preset
   MORPH -> Preset morph

   References 01..10 are built-ins; 11..20 are USER 01..10.
   Morph is continuous from 0% to 100% and interpolates all compatible tuning
   parameters live. The result becomes the current B tuning and can be saved
   into any user slot.

6. GRAPHICAL CURVES
   VIEW -> Curve graph:
     OFF / AUTO / TONE / CHROMA / MORPH / HDR->SDR

   AUTO changes the graph according to the current control group.
   Curves are calculated in Lua only when the OSD updates; they do not run as
   a second per-pixel video shader.

7. CLIPPING INDICATORS
   VIEW -> Clipping indicator:
     OFF / GAMUT / LUMA / BOTH

   GAMUT:
     magenta = Oklab gamut limiter is active

   LUMA:
     red  = upper luminance limiter is active
     blue = lower luminance boundary

   BOTH:
     yellow = gamut + upper-luma
     cyan   = gamut + lower-luma

   Non-flagged pixels are dimmed for spatial context. OFF restores normal
   output. DEBUG_VIEW=0 is a generated compile-time constant.

8. HDR -> SDR COMPRESSION
   OUTPUT -> HDR to SDR compression

     0%   = exact normal V3.1 tuned output path
     100% = original SDR source in the LINEAR hook

   Intermediate values continuously compress the complete current tuning back
   toward source SDR. This is NON-DESTRUCTIVE: the underlying tuning values do
   not get rewritten. Set it back to 0% to recover the exact tuned path.

9. VIDEO-START RESTORE
   SYSTEM -> Revert all to video-start state
   Captures A, B, step size and the Lab state at file load. The revert action
   requires OSD confirmation.

10. CONTROL-GROUP JUMP NAVIGATION
    In BROWSE, TOP/BOTTOM now jump whole groups. In EDIT they retain the
    familiar coarse/fine step control.

11. DESTRUCTIVE OSD CONFIRMATIONS
    Destructive menu actions require CENTER twice within four seconds.
    LEFT or RIGHT cancels.

BUILT-IN STATE SAFETY
---------------------
- Existing state path is unchanged.
- Existing A/B keys are unchanged.
- Old state files are accepted.
- New user slots are separate files.
- User preset saves use temp-file + rename writes.
- Runtime A/B shader swap safety remains intact.
- The persistent Shader Lab OSD remains controlled by the native LAB button.

CORE IMAGE MATH
---------------
The original V3.1 Oklab expansion/gamut architecture is retained. v6 adds only:
- compile-time clipping diagnostic mode;
- reversible output SDR compression;
- controller/preset/graph functionality.
With Clipping Indicator OFF and HDR->SDR Compression at 0%, the original V3.1
normal-return path is preserved.


============================================================
WORKSTATION v6.1 STUDIO UI + ANDROID TV REMOTE
============================================================

UI redesign:
  - compact localized translucent studio panel; no full-video dim layer
  - group breadcrumb with previous/current/next group
  - large selected-control card and live value
  - graphical normalized value slider
  - previous/next control preview
  - integrated curve graph card with grid/reference line
  - clear BROWSE vs EDIT mode styling
  - dedicated destructive-action confirmation card
  - minimal ORIGINAL HOLD badge so comparison is mostly unobstructed
  - touch and TV-remote instructions are always visible

Android TV / hardware remote while Lab is visible:
  DPAD LEFT/RIGHT = previous/next item (or -/+ while editing)
  DPAD UP/DOWN    = previous/next group (or coarser/finer while editing)
  DPAD CENTER/OK  = select/edit/done/action
  MENU            = toggle Shader Lab
  BACK/ESC        = close Shader Lab when the platform routes the key to mpv

The companion native v5.8.3 patch directly intercepts Android DPAD in
PlayerActivity while p9LabVisible is true, because stock mpvFlux consumes
DPAD LEFT/RIGHT before libmpv/input.conf can see them.

Long-hold runaway protection:
  - native touch finger-up now patches the real gesture-end Job.cancel path
  - multi-touch cancellation also sends stop
  - Lua adds a 6-second emergency watchdog so navigation can never run forever

State safety:
  This ZIP does NOT include pixel9-shader-lab-state.txt or user preset files.
  Existing A/B tuning and user presets are preserved.


============================================================
v6.1.1 — STUDIO UI RENDER FIX
============================================================

Fixes the collapsed/tiny Studio interface.

Root cause:
  mpv's ass-events overlay format splits overlay.data on newline
  characters and turns every line into its own ASS Dialogue event.

  v6.1 joined independently positioned UI/vector elements without
  newline separators, packing dozens of \pos() elements into one event.

v6.1.1:
  - writes every UI/vector object as its own ASS event
  - fixes the main Studio panel
  - fixes graphical curves
  - fixes confirmation dialogs
  - fixes the original-preview badge
  - derives ASS PlayResX from the current OSD aspect ratio
  - retains a stable 720-unit vertical coordinate system
  - automatically reflows on Android rotation/display-size changes
  - works with ultra-wide phone landscape and 16:9 Android TV

This ZIP contains no saved Lab state and no user preset files.
Existing A/B tuning, saved state, and user presets are untouched.

Diagnostic:
  script-message p9lab-layout-info
