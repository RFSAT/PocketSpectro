# Changelog

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH), so it rises with every version.

## 1.3.0 (build 10300), October 2026
- Map: fire detections are shown as flame icons (red flame with a yellow core and white outline, readable on
  street, satellite and terrain maps); detections from the simulator use a grey flame and get their own
  legend entry. The selection ring now surrounds the flame.
- One button style throughout the app:
  - every button, chip and switch has a clearly visible border, also when disabled;
  - two-state controls are green when ON and red when OFF and show their state: Acquisition ON/OFF,
    Monitoring ON/OFF, Alarm ON/OFF, Logging ON/OFF, Saving/Paused, and all switches;
  - single-choice chips (display mode, map layer, simulator mode) show the chosen option in green;
  - plain actions (Store dark, presets, Share, Delete, dialog buttons...) are outlined, the main action filled.

## 1.2.0 (build 10200), October 2026
- Simulator: fire incidents can be injected, on the Fire screen under "Simulated fire incidents":
  - Manual: "Inject fire" starts a burst of the set duration; "Hold flame on" keeps emitting until switched off.
  - Random: bursts start at random (Poisson, set mean interval), each with duration and strength varied ±50 %.
  - Strength scales the K I lines (1 = about 27 % above the continuum at 200 ms; 0.35 = the 10 % threshold).
  - Bursts ramp up and down over about 1.5 s, so the confirmation frames and alarm are exercised.
  - Detections go through the normal detector, alarm, journal and map; on the map they are titled
    "Fire detection (simulated)". Settings are remembered.

## 1.1.4 (build 10104), October 2026
- All on-screen text moved into `res/values/strings.xml` (about 370 entries, with plurals and translator
  notes), so Google Play's automatic translation can translate the interface. The app itself is unchanged
  in English.
- Not translated on purpose: the app name, units, file names, CSV/KML/session-file contents, map
  attributions and low-level driver error messages (kept in English for support).
- Small fixes found on the way: the Log screen now notices a newly connected spectrometer at once, and the
  detection date on the map follows the phone's language.

## 1.1.3 (build 10103), October 2026
- Release build shrunk with R8 (code and resources). The mapping file travels inside the bundle, so
  Play shows readable crash and ANR stack traces. The app's own classes keep their names, so saved
  settings and logged files stay compatible.

## 1.1.2 (build 10102), October 2026
- Play version code derived from the version number, so a new version can never reuse a code Play has
  already seen (Play rejected a repeated code). Codes now run 10102, 10103, ... 10200 for 1.2.0.

## 1.1.1 (build 3), October 2026
- New app icon: a spectrum coloured from violet to near-infrared with the potassium doublet; the same artwork
  as the Google Play icon (`art/icon.svg`, `art/play_icon_512.png`). Android 13+ themed (monochrome) icon added.
- Google Play feature graphic (`art/feature_graphic_1024x500.png`), rendered with the icon by `art/make_store_art.py`.
- Google Play listing screenshots (9:16, no version shown) rendered by the screenshot tests into `docs/play/`.

## 1.1.0 (build 2), October 2026
- Drivers for the classic Ocean family (OOI protocol): USB2000+ / Flame-S, USB4000 / Flame-T, USB650, USB2000,
  HR2000+, HR4000, Maya2000 Pro, Torus, Jaz; NIRQuest256/512 with external power. Experimental (ported, not yet
  hardware-tested).
- Driver for the Ocean ST / SR2/4/6 / HR2/4/6 generation (OBP2 protocol, USB-C). Experimental.
- Ocean HDX enabled on the OBP driver (experimental).
- STS: falls back to the newer raw-spectrum request (0x00101100) if a unit refuses the original one.
- Fire detection offered on any NIR unit whose calibration starts at 600 nm or more and covers 766-770 nm.
- Detector confidence (0-100 %) from line signal-to-noise, doublet presence and ratio, and peak position;
  shown live on the Fire screen and logged.
- Each journalled detection stores its spectrum and measurement details; tapping it on the map opens the
  spectrum, confidence breakdown, spectrometer, integration and GPS details.
- "Other maxima" measured against the local continuum (running median) instead of the spectrum mean
  (the Pi preset keeps the original rule).
- Map screenshots in the test suite render real tiles.

## 1.0.0 (build 1), October 2026 (first build)
First version: port of the Raspberry Pi Zero W "Wi-Fi STS-NIR" web server to a native Android app,
named PocketSpectro (com.PocketSpectro) to cover Ocean USB spectrometers generally.
- Direct USB-OTG control of the STS (Ocean Binary Protocol 0x1100), ported from SeaBreeze.
- Live counts / absorbance / %T-%R; averaging and boxcar actually applied (the Pi daemon ignored both).
- Dark (manual or built-in shutter) and reference; nonlinearity correction from the unit's coefficients.
- Fire detection mode: recommended (wavelength windows, local-continuum baseline) and Pi-identical presets.
- Timed logging with GPS tagging; spectra.csv / per-spectrum files, fire.csv, detections.kml.
- Home / About tab and Exit (with confirmation) in the tab bar.
- Device catalogue: all Ocean USB spectrometers recognised; STS supported, Spark experimental, OOI and OBP2 families planned.
- Fire detection offered only with an STS-NIR (Fire tab hidden otherwise).
- Map tab (MapLibre 13, same base layers as DBM) with fire detections and survey tracks; detection journal recorded with or without logging.
- Log tab with Recording and Sessions sub-tabs (Results merged in).
- Warning on the Fire screen while the Pi rule (whole-spectrum mean) is active.
- Release signing uses the same secret names as the other RFSAT apps.
- Built-in simulator (NIR and VIS variants); Robolectric/Roborazzi screenshot suite.
