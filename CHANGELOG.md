# Changelog

Each version's notes are one plain-text paragraph for Google Play's "What's new in this release" box,
under 500 characters, to paste as they are. Technical details live in the README and the commit history.

Versioning: 1.MINOR.PATCH. The minor number rises with new features, the patch number with fixes.
Only `appVersion` in app/build.gradle.kts is edited; the Play version code is derived from it
(1*10000 + MINOR*100 + PATCH).

## 1.6.0 (build 10600)
Every tool now explains itself: a one-line summary under the tool's name, and a "How to use" link that opens a short guide with what the tool measures, what it needs, the steps to follow and practical tips. Regular users keep an uncluttered screen; new users can learn each tool, also with the simulator, before buying a spectrometer.

## 1.5.0 (build 10500)
The Colour tool can now be used with spectrometers that cover only part of the visible range, such as the STS-NIR: it shows which part is covered and which colours can still be detected and monitored, and compares samples with a standard measured on the same unit.

## 1.4.2 (build 10402)
A maintenance release with the improvements of 1.4.1: the app displays edge to edge on all Android versions and is ready for Android 15 and later, and text fields stay above the on-screen keyboard.

## 1.4.1 (build 10401)
The app now displays edge to edge on all Android versions and is ready for Android 15 and later, and text fields stay above the on-screen keyboard.

## 1.4.0 (build 10400)
A new Tools tab brings eight measuring tools: fire detection, emission lines, vegetation and crops with an NDVI survey on the map, spectral match, concentration, colour, light source/LED and time series. Every tool also works with the simulator, as an STS-NIR, VIS or UV unit, so it can be tried before buying a spectrometer. Fire detection now ignores the daylight oxygen band next to the potassium lines.

## 1.3.0 (build 10300)
Fire detections now appear on the map as flame icons, with simulated ones in grey, and buttons are clearer: every button has a visible border, and on/off controls are green when ON and red when OFF.

## 1.2.0 (build 10200)
The simulator can now inject fire incidents, either by hand as a timed burst or a steady flame, or at random with adjustable interval, duration and strength; simulated detections are marked as such on the map.

## 1.1.4 (build 10104)
The interface is ready for translation into other languages, the Log screen reacts at once when a spectrometer is connected, and dates on the map follow the phone's language.

## 1.1.3 (build 10103)
A smaller download and better crash reports, for faster fixes.

## 1.1.2 (build 10102)
A maintenance release with no visible changes.

## 1.1.1 (build 3)
A new app icon, with a themed icon on Android 13 and later.

## 1.1.0 (build 2)
Support for more Ocean spectrometers (experimental): USB2000+/Flame-S, USB4000/Flame-T, USB650, HR2000+, HR4000, Maya2000 Pro, Torus, Jaz, NIRQuest, Ocean HDX, and the Ocean ST, SR and HR series. Fire detection now works on any near-infrared unit covering 766–770 nm and gives a confidence score, and tapping a detection on the map shows its spectrum and details.

## 1.0.0 (build 1)
First release: live spectra (counts, absorbance, %T/%R) from Ocean STS spectrometers over USB, with dark and reference, averaging and smoothing, GPS-tagged logging, potassium-line fire detection with an alarm, and a map of detections. A built-in simulator lets you try the app without hardware.
