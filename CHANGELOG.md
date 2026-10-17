# Changelog

## 1.0.0 (build 1), October 2026
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
