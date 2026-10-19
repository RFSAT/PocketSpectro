# PocketSpectro (com.PocketSpectro)

Android app (Samsung Galaxy S24, Android 8.0+, target API 36) that drives **Ocean Optics / Ocean Insight
USB spectrometers** directly over USB-OTG and processes spectra in real time. When a near-infrared unit
covering 766–770 nm is connected (the STS-NIR), it adds potassium-line (K I 766.5 / 769.9 nm)
**fire detection**. Version 1.0.0 drives the STS family; the other Ocean families are recognised and
listed below with their status.

It started as the port of the Raspberry Pi Zero W "Wi-Fi STS-NIR" web server (2015–2019), where
Apache/PHP served the UI and a C++ daemon talked to the spectrometer. On the phone, the app talks to the
spectrometer itself and shows the UI on the phone screen.

RFSAT Ltd. Version 1.4.0 (build 10400).

## Hardware

* A supported spectrometer, today the STS family (USB VID 0x2457, PID 0x4000; micro-USB on the unit).
* A data-capable OTG cable (micro-USB to USB-C for micro-USB units).
* The app opens automatically when any Ocean spectrometer is plugged in (USB device filter) and asks for
  USB access once.

## Other Ocean spectrometers

Every Ocean Optics / Ocean Insight USB spectrometer (vendor IDs 0x2457 and 0x0999) opens the app
when plugged in, and `core/DeviceCatalog.kt` decides what happens next:

| Status | Models | Protocol |
|---|---|---|
| Supported | STS-VIS / STS-NIR / STS-UV (variant named from the unit's calibration) | OBP |
| Experimental | Spark, Ocean HDX | OBP |
| Experimental | USB2000+ / Flame-S, USB4000 / Flame-T, USB650, USB2000, HR2000+, HR4000, Maya2000 Pro, Torus, Jaz | OOI (classic) |
| Experimental | Ocean ST, SR2/4/6, HR2/4/6 (USB-C) | OBP2 |
| Experimental, needs external power | NIRQuest256/512 | OOI |
| Planned | QE Pro (32-bit buffered spectra), QE65000 | OBP / OOI |

"Experimental" means the driver is ported from SeaBreeze / python-seabreeze and passes tests against
simulated devices, but has not yet run on that hardware.

Each protocol family is one class implementing `Spectrometer` (`ObpSpectrometer`, `OoiSpectrometer`,
`Obp2Spectrometer`), chosen by `SpectrometerFactory`; processing, logging and the UI are model-independent.
**Fire detection is offered only on near-infrared units** (calibrated range starting at 600 nm or more and
covering 766-770 nm, such as the STS-NIR or ST-NIR); for any other unit the Fire tab is hidden and no K-line
analysis runs.

## Screens

| Tab | Purpose |
|---|---|
| Home | Connection status and quick actions, app description, RFSAT and hardware details, spectrometer support list |
| Live | Live spectrum (counts, absorbance, %T/%R), run/stop/single, integration, averaging, boxcar, dark (manual or built-in shutter) and reference |
| Tools | The measuring tools (see below); Fire detection is one of them |
| Map | Fire detections (flame icons; grey for simulated ones) from the detection journal and survey tracks (blue) of logged sessions on a MapLibre map with Street / Satellite / Terrain layers, as in DBM; tap a detection for its details |
| Log | **Recording**: timed logging like the Pi's Acquisition Control (interval, 0 = every spectrum; max count; single CSV or one file per spectrum; GPS tagging). **Sessions**: the fire detection journal and the logged sessions; open, share or delete files |
| Setup | Source (USB / simulator), device info and calibration, acquisition, binning, nonlinearity, fire-detection parameters |
| Exit | Confirms, stops any log, releases the spectrometer and closes the app |

There is a built-in **simulator** (Setup → Use simulator) for trying the app without hardware and for
Play review. It can add a sample with absorption bands (Setup) and inject fire incidents (Fire screen →
Simulated fire incidents): manually, as a timed burst or a flame held on, or at random with a set mean
interval, duration and strength. Detections from simulated incidents go through the normal detector,
alarm, journal and map, where they are titled "Fire detection (simulated)".

## Tools

Each tool declares the wavelength range it needs; tools the connected unit cannot serve are greyed out
with the range they need. With the simulator (Setup, or "Try with the simulator" on the Tools tab) the
simulated model (STS-NIR / STS-VIS / STS-UV) can be switched on the Tools list, and every tool has a
**Simulation** panel choosing what the simulated probe looks at, so each tool can be evaluated before
buying hardware. While a white reference or blank is stored, the simulator shows the white tile or the
blank cuvette by itself.

| Tool | What it computes | Code |
|---|---|---|
| Fire detection | K I doublet above the local continuum, confidence, alarm, journal and map | `proc/FireDetector.kt` |
| Emission lines | Presets (flame tests, Hg-Ar lamp, lasers) or custom lines: centre, shift, FWHM, % above continuum, S/N; alarm and map events | `tools/EmissionLines.kt` |
| Vegetation and crops | NDVI, red-edge position (linear interpolation), CIre, WBI (900/970), PRI where covered; GPS survey points coloured by NDVI on the map | `tools/Vegetation.kt` |
| Spectral match | Library of reference spectra (`files/library/*.csv`); score = 1 − 5·RMS residual / level after scaling the library spectrum to the unknown | `tools/SpectralLibrary.kt` |
| Concentration | Beer–Lambert calibration line from standards (slope, intercept, R²) and the unknown's concentration | `tools/Quantitative.kt` |
| Colour | CIE XYZ / L\*a\*b\* under D65 (2° observer, Wyman 2013 CMF fit), sRGB swatch, CIEDE2000 against a standard | `tools/Colorimetry.kt` |
| Light source / LED | Peak, FWHM, centroid, xy, CCT (McCamy), PAR photon fraction; response correction from a lamp of known temperature; lux and PPFD after scaling to a lux meter | `tools/Quantitative.kt` |
| Time series / kinetics | Absorbance, ratio or counts at one wavelength against time; first-order fit (k, half-life, R²); CSV export | `tools/Quantitative.kt` |

Results saved with "Save to map", vegetation survey points and emission alarms go to
`files/events/tool_events.csv` and appear on the Map as coloured dots (tap for details).

The fire detector now keeps its left continuum flank below the atmospheric O2 A band (759–765 nm,
Setup → "Skip the oxygen band"): in simulated daylight the old flank lifted the K I lines to +5 / +9 %
above the continuum with no flame, close to the 10 % threshold.

## Processing chain

mean of N raw scans → − dark → nonlinearity correction (unit's own coefficients, only once a dark
exists) → boxcar (Ocean convention: w pixels each side) → absorbance −log10(S/R) or %T = 100·S/R.
Wavelengths come from the unit's calibration polynomial λ = c0 + c1·p + c2·p² + c3·p³ (binned
pixels use the mean wavelength of their group). The STS has no electric-dark pixels, so electric-dark
correction does not apply.

## Fire detection

A port of the Pi's `af3-peaks.php` (the "Detect" page):

* **Recommended preset (default):** windows on the K I lines by wavelength (±1.2 nm). Each line is
  compared with a straight-line continuum fitted through flanking regions 2–10 nm outside the doublet.
  Threshold is 10 %, with optional N-frame confirmation.
* **Pi preset:** exactly what the Pi did: one raw 200 ms scan, pixel windows 278–282 / 287–291
  (gradient indices; the peak is the next pixel), gradient zero-crossing, and each line compared with
  the **mean of the whole spectrum**. Use it to compare with old field data.

Every analysis also carries a **detector confidence** (0-100 %): the product of the lines' signal-to-noise
against the scatter of the continuum flanks (3σ → 0, 10σ → full), whether both doublet lines rise above the
noise, whether their height ratio is plausible (0.8-2.6), and how close the peaks are to 766.5 / 769.9 nm.
The "other maxima" list measures each local maximum against a running-median continuum (±12 nm).

Two findings from the Pi source:
1. The Ocean daemon stored "scans to average" and "boxcar" but never applied them. The Pi's
   detector therefore always ran on a single unsmoothed scan, despite requesting boxcar 3.
2. Comparing with the whole-spectrum mean flags any spectrum whose continuum near 766 nm sits more
   than 10 % above its own mean, even with no emission. The simulator reproduces this: see screenshot
   `04b_fire_pi_preset_no_flame.png`. The local-continuum baseline does not have this problem.

## Logged files (per session folder, under Android/data/com.PocketSpectro/files/sessions/)

* `spectra.csv`: one row per spectrum (UTC time, GPS, settings, then one column per wavelength),
  or `<prefix>_NNNNN.txt` files in the Pi daemon's text layout.
* `fire.csv`: K-line analysis for every saved spectrum (both lines, baselines, %, fire / confirmed).
* `detections.kml`: survey track plus a placemark per confirmed detection (opens in Google Earth).
* `session.txt`: device calibration and all settings.

Separately, every confirmed fire detection with a GPS fix is appended to
`Android/data/com.PocketSpectro/files/events/fire_events.csv`, whether or not a log is open. A sustained
detection is re-recorded every 10 s or after 25 m of movement. The Map tab reads this journal.

Map tiles come from OpenStreetMap, Esri World Imagery and OpenTopoMap (no API key, as in DBM). MapLibre
identifies the app in every tile request (User-Agent `com.PocketSpectro/<version> … MapLibre Android`) and
caches tiles on the phone, as OpenStreetMap's tile policy asks. If usage grows, switch the style URLs in
`ui/map/EventMap.kt` to a keyed provider such as MapTiler.

## Build

GitHub Actions (`.github/workflows/android-ci.yml`) runs the unit tests and builds the debug APK on
every push. With these repository secrets it also produces the signed Play bundle:
`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (the same names, and the same upload key, as the other RFSAT apps).

Toolchain: AGP 9.0.0, Gradle 9.7.0, Kotlin 2.3.0 (built-in Kotlin), Compose BOM 2026.06.01,
JDK 17, compileSdk/targetSdk 36, minSdk 26.

Screenshots of every screen (Robolectric + Roborazzi, simulator data, Galaxy S24 size):

    ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'   # → app/build/screenshots/

## Source map

| Package | Contents | Ported from |
|---|---|---|
| `obp` | OBP message framing and STS commands | SeaBreeze `OBPMessage`, `OBPTransaction`, STS exchanges |
| `usb` | Android USB host bulk transport | SeaBreeze `STSUSB` (first bulk IN/OUT pair) |
| `core` | Acquisition engine (single device thread), spectrometer interface, alarm | Dev Kit daemon `Spectrometer`, `Sequence` |
| `proc` | Calibration, processing, fire detector | Daemon + `af3-peaks.php` |
| `data` | Settings, GPS, session logger, KML | `FileManager`, gpsd pages |
| `sim` | Simulated STS-NIR | — |
| `ui` | Compose screens | PHP pages |

## Store assets

* `art/icon.svg`: master artwork for the launcher icon and the Play icon. The launcher's adaptive icon
  (`res/drawable/ic_launcher_*.xml`) uses the same paths.
* `art/play_icon_512.png`: Google Play icon, 512 x 512 PNG, full-bleed square (Play rounds the corners).
* `art/feature_graphic_1024x500.png`: Google Play feature graphic.
* `art/make_store_art.py` re-renders both from `icon.svg` (`pip install cairosvg pillow`).
* `docs/play/`: the eight listing screenshots, 1080 x 1920, regenerated by the screenshot tests.

## Translation

Every on-screen string is in `app/src/main/res/values/strings.xml`, with comments for translators where
the meaning is not obvious (placeholders, scientific terms, short labels). Google Play's automatic
translation (Play Console → Grow users → Translations → App strings) works from this file. Log files,
CSV/KML contents, units and settings keys stay in English so data files read the same everywhere.

## Licences

The USB protocol is ported from Ocean Optics SeaBreeze and the STS Dev Kit daemon (MIT licence,
© 2014–2015 Ocean Optics Inc.). This app is not affiliated with or endorsed by Ocean Optics / Ocean Insight.
