# PocketTrack

**Phone camera tracking for Blender.** Film with your Android phone and PocketTrack records the
footage, the camera's motion (ARCore) and a proxy cube you place in the scene. Send the scan to Blender
over Wi-Fi and you get a matching animated camera with your footage behind it, ready for CG.

```
 Android app (ARCore)                               Blender add-on
 ────────────────────                               ──────────────
 1. scan the floor                                  camera   – keyed every frame, real lens data
 2. place / move / resize a cube                    footage  – video as camera background + sound
 3. record (1080p or 4K, sound, optional depth) ──► proxy    – the cube, standing on the floor
 4. replay, trim, name it                  Wi-Fi /  points   – tracked feature points
                                           QR or zip floor   – shadow catcher
                                                    depth    – room points + rough shadow-catcher mesh
```

## Download

Get the latest **APK** and **Blender add-on** from [Releases](../../releases).

- **Phone:** an [ARCore-supported Android phone](https://developers.google.com/ar/devices), Android 8.0+.
- **PC:** Blender **4.2 or newer** (tested on 5.2 LTS).

## Features

### App
- **Live tracking** with tracked points and the detected floor shown on screen. Hints tell you when it's ready.
- **Proxy cube:** tap the floor or press **PLACE**, drag to move it, pinch to resize, or use the pad (moves relative
  to where you look), raise/lower and rotate. **Size** sets exact cm. **Floor lock** keeps it from sinking into the floor.
- **Recording:** a clean video without overlays, with one camera pose per video frame.
  - **4K** (where the phone supports it): a second high-resolution stream from the same lens via ARCore's shared
    camera, paired with ARCore's poses by sensor timestamp. Greyed out on phones that can't do it.
  - **Landscape or vertical** (the vertical mode records the full sensor upright).
  - **Zoom 1×–3×** with a slider, also while recording. The zoom level is saved per frame, and Blender animates the lens to match.
  - **Sound** from the microphone, kept in sync.
  - **Depth** (optional toggle, phones with ARCore Depth): rough room geometry collected while you record.
- **Per-frame tracking quality** (good / weak / lost).
- **Library:** a grid of scans with previews. Name a scan when you stop, or discard it. **⋮** → send to PC (scan QR or type
  the address), save .zip to `Download/PocketTrack`, share, rename, delete.
- **Replay** with sound and the track drawn on top (what Blender will show): footage / object / points / floor
  toggles, a quality strip under the scrub bar, and **non-destructive trim**.

### Blender add-on
3D Viewport → **N** → **PocketTrack** tab, and **File → Import → PocketTrack**.

- **Receive from phone:** shows a QR code and PIN in the viewport. The scan uploads over Wi-Fi and imports itself.
- **Save to:** where received scans are stored, one folder per scan named after the scan. The **dropdown** lists every
  scan in that folder, so you can import any of them.
- **What an import builds:** camera with the recorded focal length, principal point and per-frame zoom. Footage as camera
  background, sound on the timeline, the trim as the frame range. Cube, tracked points, shadow-catcher floor, and depth
  points plus a rough shadow-catcher mesh.
- **Toggles** for footage, object, points, floor and depth.
- **Tracking-quality markers** on the timeline where tracking was weak or lost.
- **Scans in this file:** switch between imported scans, or **Remove** one. That removes it from the scene and, optionally,
  deletes its received files.

## Using it

1. **Phone:** New scan → move slowly over the floor until it's found → place the cube where your subject is → **REC** →
   film → stop and name it.
2. **Blender:** PocketTrack tab → **Receive from phone**. On the phone: ⋮ → **Send to PC – scan QR code**.
   - The phone and PC must be on the same Wi-Fi.
   - On the first transfer, allow Blender through the Windows firewall on **private networks**.
3. Press **Numpad 0** to look through the camera, then play.

No Wi-Fi? ⋮ → **Save .zip to Downloads**, copy it over, then **File → Import → PocketTrack**.

### Rendering CG over the footage
The camera background only shows in the viewport. To composite without nodes:
1. Turn on **Render → Film → Transparent** and render a PNG (RGBA) sequence.
2. In the **Video Editor**, put `video.mp4` on channel 1 and the PNG sequence on channel 2 with **Blend: Alpha Over**.

The floor and depth mesh are Cycles shadow catchers, so CG shadows land on the real room.

### Tips
- Lots of light, textured surfaces, slow moves. ARCore struggles with blank walls and fast pans, and so does depth.
- Give it a few seconds of scanning before you record.
- 4K shows its detail in good light. In a dim room, motion blur makes it look much like 1080p.

## Building from source

No Android Studio needed.

**GitHub Actions:** every push builds the APK and the add-on zip (Actions tab → latest run → artifacts).

**Windows, portable** (everything lands in `.toolchain\`; delete it to uninstall):
```powershell
powershell -ExecutionPolicy Bypass -File tools\setup-android.ps1   # one time: JDK 17, Android SDK tools, Gradle
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1       # -> dist\PocketTrack.apk  (-Install to adb-install)
```

**Any OS with JDK 17 and the Android SDK:** `cd android && ./gradlew assembleDebug`

**Add-on:** zip `blender_addon/pockettrack`, or
`blender --command extension build --source-dir blender_addon/pockettrack --output-dir dist`.
Install it via Edit → Preferences → Get Extensions → ⌄ → **Install from Disk**.

**Tests:** `blender --background --factory-startup --python blender_addon/tests/test_addon.py`. These check camera
projection (landscape, vertical and zoom), import, zip, trim, quality markers, depth, the Wi-Fi receiver and Remove.

## Recording format

Each scan is a folder (or a zip) containing `video.mp4`, `track.json`, `meta.json` and, with depth, `depth.bin`.

`track.json` uses ARCore coordinates: right-handed, metres, **+Y up**, all relative to the floor anchor. The camera
looks down −Z with +Y up, like a Blender camera.

| key | content |
|---|---|
| `video` | `width`, `height`, `fps`, `frame_count`, `audio`. Frames are stamped at a constant rate, so video frame *i* = `frames[i]` |
| `camera` | `fx`, `fy`, `cx`, `cy` in pixels (origin top-left) for 1× zoom, plus the GL `projection` matrix |
| `frames` | `[t, px, py, pz, qx, qy, qz, qw, tracking, zoom]` per frame: time (s), camera position + rotation, quality (1 good · 2 weak · 0 lost), digital zoom (scales focal length and principal-point offset about the image centre) |
| `object` | cube keys `[frame, x, y, z, yaw, size_x, size_y, size_z]`, origin at the bottom centre |
| `points` | `[x, y, z, confidence]` tracked feature points |

- `meta.json`: the scan's `title`, plus the optional trim (`trim_start`, `trim_end`, frame indices).
- `depth.bin`: little-endian float32 `x, y, z` triples.
- Blender conversion: `(x, y, z)_ARCore → (x, −z, y)_Blender`.

## Project layout

```
android/                  Kotlin app (ARCore, OpenGL ES 2, MediaCodec, Media3)
  app/src/main/java/com/pockettrack/app/
    CaptureActivity.kt    AR session, cube controls, recording, 4K pairing, depth
    MainActivity.kt       library grid, send / export / rename / delete
    ReplayActivity.kt     playback with the track drawn on top, trim
    ar/                   camera background, overlays, shared camera (4K), cube state
    record/               H.264 + AAC into one MP4, track.json writer
    replay/               track loading, quality strip
    transfer/  data/      Wi-Fi upload, scan storage and zip export
blender_addon/pockettrack/
  importer.py             builds camera / footage / proxy / points / floor / depth
  receiver.py             HTTP receiver for the phone (background thread)
  qr.py                   self-contained QR encoder
  ui.py                   panel, operators, QR overlay, toggles, scan list
blender_addon/tests/      headless tests
tools/                    portable Windows toolchain + build script
```

## Known limitations
- ARCore only tracks with the main camera, so the ultra-wide and telephoto lenses can't be used. Zoom is digital.
- No lens-distortion model: ARCore's pinhole intrinsics are used as they are.
- ARCore drifts a little on long takes. Keep shots short-ish and the anchor area in view.

## License
MIT (see `LICENSE`). The Blender add-on is declared GPL-3.0-or-later in its manifest, as Blender requires for add-ons.
MIT code is GPL-compatible. ARCore is © Google, Apache-2.0.
