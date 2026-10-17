# Changelog

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH), so it rises with every version.

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
