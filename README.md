# ViuTV Patches

Morphe patches for the ViuTV Android app (`com.hktve.viutv`), adding
Picture-in-Picture (PiP) support that the app does not declare or enable.

## Patches

| Patch | Type | What it does |
|---|---|---|
| Enable Picture-in-Picture | Manifest | Adds `android:supportsPictureInPicture="true"` and `android:resizeableActivity="true"` to `com.viutv.MainActivity`, and adds `screenLayout` + `smallestScreenSize` to `android:configChanges`. Without this, Android will not allow the activity into PiP at all. |
| Force Picture-in-Picture on leave | Bytecode | In `ReactExoplayerViewManager.createViewInstance()`, forces `enterPictureInPictureOnLeave = true` on every created player view, so the player enters PiP when leaving the app. The app's JavaScript never sets this prop, so the manifest patch alone is not enough. |

Compatible with ViuTV **2.17.0** (versionCode `217000397`) only.
Fingerprints are pinned to that version; an app update will require re-verification.

## Build

Trigger the `Release` workflow (Actions → Release → Run workflow) with a
version number. It builds `patches/build/libs/patches-<version>.mpp` and
publishes it as a GitHub Release asset.

See the full Chinese step-by-step guide in
[`../README.md`](../README.md) (parent folder).

## Limitations (untested)

These patches were written from verified decompiled bytecode (ViuTV 2.17.0),
but they have **not** been built or installed on a device — there is no
Android environment in the VM used to write them. Build and test them
yourself before relying on them. ViuTV's behaviour on a re-signed APK
(integrity checks) is unverified. PiP must also be allowed for ViuTV in
Android system settings (Settings → Apps → ViuTV → Picture-in-picture).
