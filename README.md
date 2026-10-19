# PocketSpectro (com.PocketSpectro)

Android app (Samsung Galaxy S24, Android 8.0+, target API 36) that drives **Ocean Optics / Ocean Insight
USB spectrometers** directly over USB-OTG and processes spectra in real time. When a near-infrared unit
covering 766–770 nm is connected (the STS-NIR), it adds potassium-line (K I 766.5 / 769.9 nm)
**fire detection**; the Tools tab adds further measuring tools (see below). The STS family is fully
supported; the other Ocean families are recognised and listed below with their status.

It started as the port of the Raspberry Pi Zero W "Wi-Fi STS-NIR" web server (2015–2019), where
Apache/PHP served the UI and a C++ daemon talked to the spectrometer. That system was the first
implementation of fire detection with the Ocean Optics NIR microspectrometer, developed by RFSAT Limited
in the [AF3 project (FP7-SEC)](https://rfsat.com/index.php/en/projects-and-activities/completed/af3-fp7-sec).
On the phone, the app talks to the spectrometer itself and shows the UI on the phone screen.

RFSAT Limited. Version 1.7.0 (build 10700).

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

Each tool screen starts with a one-line summary and a **How to use ›** link that opens a short guide
(what the tool does, what it needs, numbered steps and tips). The texts are the `guide_<tool>_what`,
`guide_<tool>_steps` and `guide_<tool>_tips` strings in `res/values/strings.xml`.

| Tool | What it computes | Code |
|---|---|---|
| Fire detection | K I doublet above the local continuum, confidence, alarm, journal and map | `proc/FireDetector.kt` |
| Emission lines | Presets (flame tests, Hg-Ar lamp, lasers) or custom lines: centre, shift, FWHM, % above continuum, S/N; alarm and map events | `tools/EmissionLines.kt` |
| Vegetation and crops | NDVI, red-edge position (linear interpolation), CIre, WBI (900/970), PRI where covered; GPS survey points coloured by NDVI on the map | `tools/Vegetation.kt` |
| Spectral match | Library of reference spectra (`files/library/*.csv`); score = 1 − 5·RMS residual / level after scaling the library spectrum to the unknown | `tools/SpectralLibrary.kt` |
| Concentration | Beer–Lambert calibration line from standards (slope, intercept, R²) and the unknown's concentration | `tools/Quantitative.kt` |
| Colour | CIE XYZ / L\*a\*b\* under D65 (2° observer, Wyman 2013 CMF fit), sRGB swatch, CIEDE2000 against a standard. With a unit covering only part of 380–780 nm (at least 40 nm, e.g. STS-NIR 636–780 nm) it runs in partial mode: it reports the covered range, the share of the eye's colour response it holds and the hues it can follow, extends the reflectance beyond the range with the nearest measured value, labels L\*a\*b\* as approximate, drops the swatch and is meant for ΔE monitoring against a standard measured on the same unit | `tools/Colorimetry.kt` |
| Light source / LED | Peak, FWHM, centroid, xy, CCT (McCamy), PAR photon fraction; response correction from a lamp of known temperature; lux and PPFD after scaling to a lux meter | `tools/Quantitative.kt` |
| Time series / kinetics | Absorbance, ratio or counts at one wavelength against time; first-order fit (k, half-life, R²); CSV export | `tools/Quantitative.kt` |
| Sun photometer | Direct-sun aerosol optical depth at 440/500/675/870/1020 nm, Ångström exponent and precipitable water vapour (940 nm band, T = exp(−a(mW)^b)); Beer–Lambert–Bouguer with Rayleigh and ozone terms; solar position and Kasten–Young airmass from GPS and time; V₀ from known values or a Langley / modified-Langley plot | `tools/SunPhotometer.kt`, `tools/Solar.kt` |
| UV index | Erythemal (CIE / ISO 17166) weighting of the solar UV, UV-B share; one scale factor against a reference UV index | `tools/UvIndex.kt` |
| Lamp survey | Lamp technology from spectral fingerprints (Na D lines and self-reversal, Hg 436/546 nm, Eu 611 nm, LED blue pump, smooth thermal continuum), CCT, blue share, melanopic DER; GPS survey on the map | `tools/LampClassifier.kt` |
| Plant fluorescence | Sun-induced chlorophyll fluorescence by three-band Fraunhofer line discrimination in the O2-A (760 nm) and O2-B (687 nm) bands | `tools/Fluorescence.kt` |
| Water (UV) | Nitrate-N from A220 − 2·A275 (Standard Methods 4500-NO3⁻ B, calibratable), UV254 and SUVA | `tools/LabMethods.kt` |
| Teach and predict | PLS1 regression (NIPALS) on log(1/R) with SNV / SNV + Savitzky–Golay derivative, components chosen by leave-one-out cross-validation (RMSECV, Q²); models in `files/predict/*.csv` | `tools/Chemometrics.kt` |
| Raman | Wavelength → Raman shift, fluorescence removal by iterative polynomial (modpoly), peak picking, matching to 15 built-in materials and a user library with ±20 cm⁻¹ laser offset search | `tools/Raman.kt` |
| Olive oil | K232, K264, K268, K272 and ΔK (Reg. (EEC) 2568/91 limits for extra virgin and virgin) | `tools/LabMethods.kt` |
| Drink colour | Beer EBC = 25·A430, SRM = 12.7·A430 with the A700 clarity check; wine colour intensity and hue (OIV); honey mm Pfund = −38.70 + 371.39·A635 and USDA grade | `tools/LabMethods.kt` |
| Temperature | Grey-body Planck fit (golden-section search on log T) to the response-corrected spectrum; on the Fire tool the flame continuum (fire minus median background) gives the flame temperature and flags sun glints | `tools/Pyrometry.kt` |
| Film thickness | Fringe period in 1/λ by FFT (d = f / 2n), refined by a normal-incidence thin-film model on glass, silicon, water or metal | `tools/Optics.kt` |
| Filters and eyewear | Luminous transmittance (D65) and ISO 12312-1 category, UV-A and 380–400 nm (UV400), blue 415–455 nm, 50 % cut-on, optical density with a noise-floor lower bound | `tools/Optics.kt` |
| Health check | Dark level, read noise and hot pixels from 10 dark scans; linearity at ¼, ½ and full integration; Hg-Ar wavelength shift; shared text report | `tools/HealthCheck.kt` |
| Test-kit reader | DPD chlorine (515 nm), molybdenum-blue phosphate (880 / 710 nm), indophenol ammonia (655 nm), Griess nitrite (540 nm), phenanthroline iron (510 nm) or a custom wavelength; typical response or one-standard calibration, range warning | `tools/LabMethods2.kt` |
| pH by indicator | Two-wavelength ratio method: pH = pKa + log₁₀((R − e1)/(e2 − R·e3)) for bromocresol green, bromothymol blue, phenol red, m-cresol purple and thymol blue; end members measured or typical | `tools/LabMethods2.kt` |
| Fluorimeter | Emission above the blank in the preset window (chlorophyll-a, phycocyanin, fluorescein, quinine, oil, UV security marks), counts/s, LED leakage, one-standard calibration | `tools/LabMethods2.kt` |
| DNA and protein | A230/A260/A280 with A320 background correction; 50 / 40 / 33 µg/mL per A260 (dsDNA / RNA / ssDNA), protein A280 = 1 mg/mL; path and dilution | `tools/LabMethods2.kt` |
| Growth curve | OD600 recorded at an interval; exponential phase found by a sliding window on ln OD (R² ≥ 0.97, steepest slope): µ, doubling time, lag, maximum OD | `tools/LabMethods2.kt` |
| Physics lab | Discharge-tube identification against NIST lines (H, He, Ne, Hg, Na, Ar, Kr, Xe); Rydberg constant from the Balmer lines (air → vacuum); Planck's constant from LED wavelength and turn-on voltage | `tools/Identify.kt` |
| Water colour | Forel-Ule index from the hue angle of the water-leaving reflectance (Novoa et al. 2013 transitions), R705/R665 chlorophyll ratio, phycocyanin trough at 620 nm, NIR reflectance; reflectance per second so water and panel can use different exposures | `tools/Identify.kt` |
| Leaf chlorophyll clip | log₁₀(T940 or T780 / T650) × k (SPAD-like, k = 50 or from one SPAD reading), Markwell chlorophyll (µg/cm²) | `tools/LabMethods2.kt` |
| Greenhouse light | PPFD from the calibrated spectrum, photon shares (UV-A, blue, green, red, far red), R:FR (655–665 / 725–735 nm), daily light integral logger | `tools/LabMethods2.kt` |
| Ozone and NO₂ (experimental) | Total ozone from ln(I310/I330) with Rayleigh correction, L₀ from a known column or a Langley series; NO₂ slant column difference by DOAS (cubic high-pass, 425–490 nm) against a stored reference sun spectrum, with an imported cross-section file (synthetic cross-section only in the simulator) | `tools/LabMethods2.kt` |
| Soil colour | CIE L\*a\*b\* (D65) matched by CIEDE2000 to the Munsell renotation data (soil-chart pages or all chips, neutral greys from ASTM D1535) | `tools/Identify.kt` |
| Colour rendering | CCT and Duv (exact Planckian search), CIE 13.3 Ra and R1–R14 (TCS01–14), IES TM-30-18 / CIE 224 Rf, Rg and 16 hue-bin chroma shifts (99 CES, CAM02-UCS); CIE tables in `src/main/resources/data/`, checked against colour-science 0.4.7 | `tools/ColourQuality.kt` |
| Display check | Chromaticity of white, red, green and blue patches (full-screen patches can be shown on the phone itself), gamut area against sRGB and coverage of sRGB, DCI-P3 and Rec.2020 in u′v′ (Sutherland–Hodgman clipping), white-point CCT | `tools/LabMethods2.kt` |
| Pigment identification | FORS features (inflection points, minima, maxima, NIR, flat spectra) matched to 17 historical pigments; score = found × precision × explained share | `tools/Identify.kt` |
| Gem tester | Absorption bands in transmittance (≥ 3 % deep) or fluorescence lines matched to diagnostic lines of 12 gems and imitations | `tools/Identify.kt` |
| Light dose (melanopic) | Melanopic EDI and illuminance per second with auto-exposure over 2 ms–2 s (a dark per step); logger and day summary against Brown et al. 2022 (≥ 250 lx day, ≤ 10 lx evening, ≤ 1 lx sleep) | `tools/LabMethods2.kt`, `tools/ToolHub.kt` |
| UV dose | Erythemal dose in SED from the UV index (1 UVI = 0.025 W/m²), share of the typical MED by Fitzpatrick type, time to it | `tools/LabMethods2.kt` |
| Skin indices | Diffey erythema and melanin indices (560 / 650 nm), relative carotenoid index (480 nm against 440 / 520 nm), baseline and change, readings logged with a site label | `tools/LabMethods2.kt` |
| Pulse | Photoplethysmogram from the red / NIR band through a fingertip: rate by autocorrelation, perfusion index, RMSSD, signal quality (no SpO₂) | `tools/LabMethods2.kt` |
| Exposure diary | Daily CSV logs (`files/exposure/<kind>_YYYYMMDD.csv`) zipped with a README (participant code, app version, instrument), shared or POSTed to a study's https address | `data/ExposureLog.kt`, `ui/ToolScreensG.kt` |

### Exposure and wellness tools (non-medical)

Light dose, UV dose, skin indices, pulse and the exposure diary are for wellness, education and research.
They open only after a one-time notice ("not a medical device and does not diagnose, treat, cure or
prevent any medical condition"; consult a healthcare professional), and each screen repeats a short
reminder. There is no blood-oxygen (SpO₂) measurement. Before publishing this version on Google Play:

* complete the **Health apps declaration** (Play Console → App content) as a wellness / research app,
  not a medical device;
* add a **privacy policy URL** stating that measurements stay on the phone until the user shares or
  uploads them, and that uploads go only to the address the user enters;
* keep the external spectrometer named in the store description.

For research use (for example ENACT exposome studies), the study team provides the participant code,
the upload endpoint, informed consent and ethics approval. The upload is a plain HTTPS POST of the zip
(`Content-Type: application/zip`, header `X-Participant`).

### Application kits

Chips at the top of the Tools tab group the tools for one job: food authenticity, plastics sorting,
fake medicines, wildfire network, ENACT personal exposure, precision farming, water quality, cultural
heritage and school lab (`Kit` in `ui/ToolScreensG.kt`).

### Light calibration and auto-exposure

From 1.8.0 the Light tool works in counts per second, so its lux scale holds at any integration time;
**the lux scale must be set again after updating**. "Prepare darks" (Light dose, Greenhouse) records a
dark for each exposure step (2 ms to 2 s) so the loggers can follow light from moonlight to sunlight.

The Light tool also shows melanopic DER and EDI (CIE S 026, action spectrum rebuilt from the
Govardovskii template and lens model; equal-energy ratio 0.904 vs 0.906, D65 efficacy 1.324 vs
1.326 mW/lm) and the blue-light hazard efficacy (IEC 62471 B(λ)) (`tools/LightHealth.kt`).

Phone extras: "Save to map" has a camera button that stores a photo with the result (shown on the
Map card, files in `files/events/photos`); sample-name fields have a QR / barcode scanner (Google
code scanner, no camera permission); and Setup → Fire-watch alerts posts a JSON message for every
new confirmed fire to a web address (`core/AlertSender.kt`).

The simulator has a scene for every new tool: direct sun and global UV skies with a virtual clock
(including a fast morning for Langley plots), street lamps, glowing bodies, Raman spectra of the
library materials, films, filters and eyewear, fruit with known sugar content, beers, wines, honeys,
olive oils and waters, and for the 1.8.0 tools cultures, test kits, indicators, fluorescence, DNA, waters, leaves, gems, pigments, skin, a fingertip pulse, displays and a whole simulated day of personal light. `NewToolsTest` and `NewToolsTest2` check each tool against these scenes.

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

GitHub Actions (`.github/workflows/android-ci.yml`) runs the unit tests on every push and, with these
repository secrets, builds the signed Play bundle and the signed release APK (no debug APK):
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
| `core` | Acquisition engine (single device thread), spectrometer interface, alarm, fire-watch alerts | Dev Kit daemon `Spectrometer`, `Sequence` |
| `proc` | Calibration, processing, fire detector | Daemon + `af3-peaks.php` |
| `data` | Settings, GPS, session logger, KML, tool events, exposure logs | `FileManager`, gpsd pages |
| `sim` | Simulated STS-NIR / VIS / UV with a scene for every tool | — |
| `tools` | The measuring tools' science: colorimetry, colour rendering (CRI, TM-30), vegetation, emission lines, Beer–Lambert, kinetics, sun photometry, ozone and NO₂, UV index, lamp recognition, fluorescence, chemometrics (PLS), Raman, lab methods (test kits, pH, fluorimetry, DNA, growth), identification (Munsell, Forel-Ule, physics lab, gems, pigments), pyrometry, thin films, filters, exposure and physiology, health check | — |
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
