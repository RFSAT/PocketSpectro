# Changelog

Each version's notes are written for Google Play's "What's new in this release" box: plain text, under
500 characters, to paste as they are. Technical details live in the README and the commit history.

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH).

## 1.4.0 (build 10400)
- New Tools tab with eight measuring tools: fire detection, emission lines, vegetation and crops (NDVI survey on the map), spectral match, concentration, colour, light source/LED and time series.
- Every tool works with the simulator (choose STS-NIR, VIS or UV), so it can be tried before buying a spectrometer.
- Fire detection now ignores the daylight oxygen band next to the potassium lines.

## 1.3.0 (build 10300)
- Fire detections now appear on the map as flame icons; simulated ones are grey.
- Clearer buttons: every button has a visible border, and on/off controls are green when ON and red when OFF.

## 1.2.0 (build 10200)
- Simulator: inject fire incidents by hand (a timed burst or a steady flame) or let them occur at random, with adjustable interval, duration and strength. Simulated detections are marked as such on the map.

## 1.1.4 (build 10104)
- The interface is ready for translation into other languages.
- The Log screen reacts at once when a spectrometer is connected.
- Dates on the map follow the phone's language.

## 1.1.3 (build 10103)
- Smaller download.
- Better crash reports, for faster fixes.

## 1.1.2 (build 10102)
- Maintenance release; no visible changes.

## 1.1.1 (build 3)
- New app icon, with a themed icon on Android 13 and later.

## 1.1.0 (build 2)
- More Ocean spectrometers (experimental): USB2000+/Flame-S, USB4000/Flame-T, USB650, HR2000+, HR4000, Maya2000 Pro, Torus, Jaz, NIRQuest, Ocean HDX, Ocean ST, SR and HR series.
- Fire detection on any near-infrared unit covering 766–770 nm, with a confidence score.
- Tap a detection on the map to see its spectrum and details.

## 1.0.0 (build 1)
- First release: live spectra (counts, absorbance, %T/%R) from Ocean STS spectrometers over USB, dark and reference, averaging and smoothing, GPS-tagged logging, potassium-line fire detection with alarm, and a map of detections. A built-in simulator lets you try the app without hardware.
