# Yaesu CAT Control ADV

**An all-in-one Android station companion for the Yaesu FTX-1 — CAT control, a neural CW decoder with pileup mode, RTTY with a neural decoder and FSK transmit, a CW keyer and trainer, a logger, DX Cluster and PSK Reporter — all over a single USB-C cable.**

Author: **Eugen Trifu, YO9VA** · License: **MIT** · Platform: Android 8.0+ (API 26)

---

## Contents

- [What it is](#what-it-is)
- [Hardware and requirements](#hardware-and-requirements)
- [Features by screen](#features-by-screen)
  - [COMMANDS](#commands)
  - [STATION SETTINGS](#station-settings)
  - [RTTY CENTER](#rtty-center)
  - [MACROS EDITOR](#macros-editor)
  - [CW AI DECODER](#cw-ai-decoder)
  - [CW TRAINER](#cw-trainer)
  - [LOGGER](#logger)
  - [LOG STATISTICS](#log-statistics)
  - [DX CLUSTER](#dx-cluster)
  - [PSK REPORTER](#psk-reporter)
  - [MY SETTINGS](#my-settings)
- [Hidden settings (3-second long press)](#hidden-settings-3-second-long-press)
- [Gestures](#gestures)
- [Typical workflows](#typical-workflows)
- [Signal processing in detail](#signal-processing-in-detail)
  - [CW: neural decoding](#cw-neural-decoding)
  - [RTTY: three decoders](#rtty-three-decoders)
  - [Zero-beat (tap to tune the radio)](#zero-beat-tap-to-tune-the-radio)
- [Building from source](#building-from-source)
- [Tests](#tests)
- [Project structure](#project-structure)
- [Credits](#credits)
- [License](#license)
- [Disclaimer](#disclaimer)

---

## What it is

The Yaesu FTX-1 exposes, over one USB-C connection, two virtual serial ports (CAT and DATA) and a USB audio codec. This app uses all three from an Android phone or tablet, so the phone becomes a complete operating position:

- **CAT control** of the radio (frequency, mode, PTT, keyer, filters, power, DNR, …) through the CAT port.
- **Receive audio** from the radio's USB codec for the CW and RTTY decoders, the waterfalls and a "listen" monitor.
- **Transmit**: RTTY as true FSK keyed on the DATA port's DTR/RTS lines with nanosecond-scheduled timing, CW through the radio's keyer and memories, and voice from the phone microphone or a Bluetooth headset sent to the radio over USB audio.

Everything runs on the phone. The neural decoders (CW and RTTY) and the neural noise reduction run locally with ONNX Runtime and native code — no internet connection is needed for operating. Internet is only used by the optional online services (QRZ.com / HamQTH lookups, DX Cluster, PSK Reporter).

## Hardware and requirements

| | |
|---|---|
| Radio | Yaesu **FTX-1** (Field or Optima), connected with a USB-C cable |
| Phone / tablet | Android **8.0 (API 26)** or newer, **arm64-v8a** or **armeabi-v7a**, with USB host (OTG) |
| Radio side | The FTX-1 enumerates as a dual CP210x virtual COM port (CAT + DATA) plus a USB audio device |
| Radio menu | For RTTY FSK and PC keying, set the FTX-1's RPTT / PC KEYING lines (RTS or DTR) to match the app's **USE: RTS / USE: DTR** setting |
| Optional | A Bluetooth headset for hands-free voice PTT |

Permissions requested: USB device access, microphone (to capture the radio's USB audio and the phone/headset mic), Bluetooth (headset), location (GPS grid locator in the logger), notifications and a foreground service (DX Cluster in the background), and "display over other apps" (to keep the screen on during operation).

## Features by screen

Open the side menu (≡) to switch between screens. Buttons with an **underlined caption** have extra settings that appear with a **3-second long press**.

### COMMANDS

The main operating screen.

- **Connect** to the radio (USB permission is requested on first use); live connection status.
- **Mode buttons**: LSB, USB, CW-U, RTTY-L.
- **PTT** (momentary or toggle), keyed through the DTR or RTS line. Hidden settings (long press): toggle mode, **USE: RTS / DTR**, and **MIC: BT HEADSET / PHONE** — the transmit audio source.
- **Voice transmit over USB**: the phone microphone or a Bluetooth headset is streamed to the radio's USB audio input while PTT is pressed.
- **🔊 LISTEN**: monitor the radio's receive audio on the phone speaker, with optional **DENOISE** (HamNoise neural noise reduction for CW and voice), LEVEL (wet/dry), GAIN and BUFFER controls.
- **CW keyer**: on-screen DIT/DAH paddles, IAMBIC A/B, REV, speed (WPM), sidetone, **BK-IN**, **APF**, **WIDTH**, and **ZERO IN** (the radio's CW auto zero-in).
- **CW MESSAGES / DVS VOICE**: trigger the radio's CW keyer memories or voice (DVS) memories; STOP TRANSMISSION.
- **CW training mode**: practise on the paddles without keying the radio.

### STATION SETTINGS

- Radio model and **power adjustment**.
- **D-Level** spectrum scope reference level.
- Radio **DNR** on/off and level.
- CW keyer speed.
- **KEEP SCREEN ON**.
- **LIVE CAT MONITOR**: every CAT command sent and received, with scroll lock, a console to type raw CAT commands, and long-press to copy a line.

### RTTY CENTER

A complete 45.45-baud / 170 Hz RTTY station.

- **Waterfall** with tap-to-tune (drag to fine-tune) and Mark/Space markers; **NORM / REV**; **A-TUNE** (automatic Mark/Space detection); live **SNR**.
- **DECODER: CHEBYSHEV / MATCHED FILTER / DEEPRTTY** — see [RTTY: three decoders](#rtty-three-decoders). With DEEPRTTY the confirmed text appears in the terminal and the not-yet-confirmed tail is shown in grey below it, refreshed every second.
- Frequency tracking: **OFF / AFC / PLL**.
- **NB** (noise blanker), **DNR** (band-pass), **SNR-G** (SNR gating), **ATC** (automatic threshold correction for selective fading / QSB), Sensitivity and Filter width.
- **ITA2 / US-TTY** character tables (receive and transmit).
- **Transmit**: true **FSK** on the DATA port (DTR or RTS), with a drift-free time-anchored bit clock and 1.5 stop bits; **DIDDLE** (LTRS fill while idle); live typing, SEND, a queue for messages; **M-CR** to insert a line break at the cursor.
- **Macros** with `{call}` placeholder (filled with the last tapped callsign).
- **Clickable callsigns** in the receive terminal: tap to keep a callsign for the logger and the `{call}` macros; double-tap to open it on QRZ.com.

### MACROS EDITOR

Edit the 8 RTTY macros (title + content), with the `{call}` placeholder.

### CW AI DECODER

A real-time neural Morse decoder based on **DeepCW** (e04).

- **MODE: NORMAL** — one station, decoded inside the red band on the waterfall; **BW** 100–800 Hz.
- **MODE: PILEUP** — finds up to 5 CW stations in 200–2000 Hz automatically and decodes each one separately. Each station's text **accumulates** on its own line (`[712 Hz]: …`), and a green marker with its label is drawn on the waterfall. Unkeyed carriers and mains hum are ignored.
- **STREAM: GAPS / WINDOWS** — two streaming methods for confirming text in NORMAL mode: at word gaps, or from overlapping 12-second windows.
- **DETECT TONE** — finds the strongest tone in the last 10 s and tunes to it.
- **Zero-beat** — tapping a signal on the waterfall, pressing DETECT TONE, or clicking a station in the pileup list **moves the radio** so that you transmit on the correspondent's frequency, then fine-tunes with the radio's **ZERO IN**. See [Zero-beat](#zero-beat-tap-to-tune-the-radio).
- **DENOISE** (HamNoise CW model) with LEVEL and GAIN.
- **M1–M5** CW messages sent through the radio's keyer.
- Highlighted and clickable callsigns; repair of callsigns split by missing or extra spaces.

### CW TRAINER

Sending practice on the on-screen paddles, with the app decoding what you send:

- Modes: **GROUPS** (random letter groups, adjustable length), **CALLSIGNS**, **QSO** (realistic QSO phrases, step by step), **FREE** (key anything).
- Immediate feedback: wrong characters, unknown combinations, pauses that are too long inside a word, words run together.
- Score: characters right first time, spacing errors, characters you most often get wrong.

### LOGGER

- QSO entry with **GET FREQ/MODE** from the radio, UTC time (tap the header to refresh), RST, band from frequency.
- **QRZ.com** lookup (name, QTH, grid), with **HamQTH** as a free fallback for the grid; double-tap QRZ to open the profile in the browser.
- **GPS** grid locator for your own position.
- QSO history: tap a QSO to edit it (**SAVE CHANGES** / cancel); long press to delete it, or **Delete ALL** (with an ADIF backup first).
- **ADIF** export, import and share (e-mail / cloud).

### LOG STATISTICS

Statistics of the log, filters, and **FILL MISSING GRIDS** from QRZ / HamQTH.

### DX CLUSTER

- Telnet DX Cluster client running as a **foreground service** (stays connected in the background), with keep-alive.
- Band and mode filters, callsign search, cluster commands.
- **Tap a spot to tune the radio** (frequency and the matching mode: CW, RTTY, otherwise LSB below 10 MHz / USB above).

### PSK REPORTER

"Who hears me" — reception reports for a callsign from PSKReporter.info, with band/mode filters and optional automatic refresh (respecting the service's rate limit). Tap a report to tune the radio; double-tap it to open the receiving station on QRZ.com.

### MY SETTINGS

- **QRZ.com** and **HamQTH** credentials (stored encrypted with Android Keystore, via Jetpack Security), with connection tests.
- **DX Cluster** servers (several saved servers, with per-server credentials).
- **Backup** of all app settings and RTTY macros — optionally **encrypted** with a password (AES-GCM-256, PBKDF2 key); restore.
- **ADIF** export / import / share.
- **Local file server**: a small web server, protected by an access token and stopping automatically after 3 minutes, to download (or delete) exported backup and ADIF files from a browser on the same Wi-Fi.

## Hidden settings (3-second long press)

To keep the screens uncluttered, some settings are hidden behind their button. A button whose caption is **underlined** has such a panel: **hold it for 3 seconds** to show it. A normal tap keeps doing what the button always does.

| Screen | Button | Tap | Hold 3 s shows | Hides |
|---|---|---|---|---|
| COMMANDS | <ins>PTT</ins> | Transmit (momentary: while held; toggle: on/off) | **TOGGLE MODE** checkbox, **USE: RTS / DTR** (PTT line), **MIC: BT HEADSET / PHONE** (voice source) | 3 s after the last change |
| COMMANDS | <ins>DENOISE</ins> (next to 🔊 LISTEN) | DENOISE on/off; switching it on opens the panel | **LEVEL** 0–100 % (wet/dry), **GAIN** 0.00–2.00×, **BUFFER** 40/60/90/120/160 ms, **KEEP CONTROLS VISIBLE** | 5 s after the last change, unless KEEP CONTROLS VISIBLE is ticked |
| CW AI DECODER | <ins>DENOISE</ins> | DENOISE on/off; switching it on opens the panel | **LEVEL**, **GAIN**, **KEEP CONTROLS VISIBLE** | 5 s after the last change, unless locked |
| RTTY CENTER | <ins>PTT</ins> | Manual PTT on/off | A row with **DIDDLE**, **USE: RTS / DTR** (FSK line), **ITA2 / US-TTY** | After 5 s (extended to 8 s by each touch), or hold again |
| RTTY CENTER | <ins>NB</ins> | Noise blanker on/off | Threshold slider ×2.0–×12.0 | 5 s after you release the slider, or hold again |
| RTTY CENTER | <ins>DNR</ins> | Band-pass on/off | Width slider 250–1500 Hz (about 437 Hz minimum with MATCHED FILTER) | 5 s after release, or hold again |
| RTTY CENTER | <ins>SNR-G</ins> | SNR gating on/off | Gate threshold slider ×1.0–×3.0 | 5 s after release, or hold again |

Notes:

- The DENOISE panels open with a long press only while DENOISE is on.
- With PTT in toggle mode, a hold that opens the settings does not switch the transmitter on. In momentary mode the radio transmits for as long as you hold the button, so open the PTT settings with the radio on a dummy load or with the power turned down — or switch to toggle mode first.
- The RTTY PTT row opens only while PTT is off.
- These settings are remembered between sessions, except NB (switch and threshold) and DIDDLE, which start from their defaults each time.

## Gestures

| Where | Gesture | Result |
|---|---|---|
| RTTY and CW terminals | Tap a highlighted callsign | Keeps it for the logger and the `{call}` macros ("📋 CALL saved") |
| RTTY and CW terminals | Double-tap a callsign (within 0.5 s) | Opens it on QRZ.com in the browser |
| RTTY terminal | Tap a callsign while the live input box has the focus | Also inserts `CALL DE MYCALL KN` at the cursor (needs MY CALL in MY SETTINGS) |
| RTTY and CW terminals | Callsign colours | Green: valid callsign found in MASTER.SCP; gold: valid format, not in MASTER.SCP. The text itself is never corrected |
| RTTY waterfall | Tap / drag | Puts the Mark/Space markers on the signal / fine-tunes them |
| RTTY CENTER | Tap a macro | Inserts its text (with `{call}` filled in) into the live input; **SEND** transmits it |
| CW AI waterfall, NORMAL | Tap a signal | Zero-beat: the radio moves so that the tapped tone falls on your CW pitch, then ZERO IN |
| CW AI waterfall, PILEUP | Tap a green station line | Selects it (turns red) and zero-beats on it |
| CW AI pileup list | Tap the red/green `[712 Hz]:` prefix | Same as tapping the station on the waterfall |
| LOGGER | Tap the header | Puts the current UTC time in the time field |
| LOGGER | Tap / double-tap **QRZ.COM** | Look up the callsign / open its QRZ.com page |
| LOGGER history | Tap / long press a QSO | Edit it / delete it (or Delete ALL) |
| DX CLUSTER | Tap a spot | Tunes the radio (frequency + mode) and keeps the DX callsign for the logger |
| PSK REPORTER | Tap / double-tap a report | Tunes the radio / opens the receiving station on QRZ.com |
| LIVE CAT MONITOR | Long press a line | Copies it to the clipboard |

## Typical workflows

**Voice QSO (COMMANDS).** Connect → choose LSB/USB → (once) hold <ins>PTT</ins> 3 s and pick **MIC: PHONE** or **BT HEADSET** → hold PTT (or tap it in toggle mode) and talk. Turn on 🔊 LISTEN with DENOISE to hear the band on the phone.

**RTTY QSO (RTTY CENTER).** Tap the signal on the waterfall (or **A-TUNE**) → check NORM/REV → choose **DECODER: DEEPRTTY** for weak or fading signals → tap the other station's callsign → type, or tap a macro, and **SEND**. Before the first transmission, hold <ins>PTT</ins> 3 s and make sure **USE: RTS / DTR** matches the FTX-1 menu.

**CW QSO (CW AI DECODER).** START the decoder → tap the signal on the waterfall or press **DETECT TONE** (the radio zero-beats on it) → read the text, tap the callsign → answer with **M1–M5** or the keyer on COMMANDS. On a crowded frequency switch to **MODE: PILEUP** and tap the station you want to work.

**Logging (LOGGER).** **GET FREQ/MODE** → the callsign from the last tap is already in the call field → **QRZ.COM** fills name, QTH and grid → add the QSO. Export ADIF from LOGGER or MY SETTINGS.

**Hunting DX (DX CLUSTER / PSK REPORTER).** Connect to a cluster server, filter by band and mode, tap a spot → the radio tunes there; switch to the CW or RTTY screen to decode. PSK REPORTER shows who hears your own signal.

## Signal processing in detail

### CW: neural decoding

The CW AI decoder runs e04's **DeepCW** models (2026-10 release) with ONNX Runtime:

- Audio from the radio is resampled to 9600 Hz. The decoder builds a spectrogram of 65 bins at 12.5 Hz around the tuned tone (STFT 768 / hop 144, reflect padding, `noise_q90_log1p` normalisation) and the model outputs CTC character probabilities every 30 ms.
- The main model decodes cleanly from slow hand-sent CW up to about **45 WPM**. Measured on synthetic CW (white noise, SNR in 2.5 kHz): **0 % CER at −8 dB** for 15–45 WPM, and still usable around −12 dB.
- **Pileup**: stations are found by a DSP score per frequency bin (keyed level vs. the band's median, with keying-sideband masking and a keying check), and each station is decoded with a dedicated 3-bin narrow model. Each station's text is accumulated from overlapping 8-second windows merged by character time position.
- **Streaming**: text is confirmed either at word gaps or from overlapping 12-s windows merged by character position (the method of the DeepRTTY receiver), so no character is duplicated or lost at window boundaries.

### RTTY: three decoders

| Decoder | How it works | Best for |
|---|---|---|
| **CHEBYSHEV** | Mark/Space resonators, a 45 Hz decision low-pass filter, start-bit detection and a **character flywheel** that predicts the next start bit on continuous streams; optional **ATC** for selective fading | Low CPU, instant character-by-character output |
| **MATCHED FILTER** | Sliding one-bit correlators | Kept for comparison |
| **DEEPRTTY** | e04's neural **DeepRTTY** model: the audio is mixed down to 3200 Hz around 800 Hz (complex mixer + FIR decimator, spectral flip in REV), decoded in 12-s windows every ~8 s and merged; a provisional decode of the tail is shown in grey every second | Weak signals, QRN and QSB |

Measured with the app's own code (character error rate on synthetic RTTY in white noise, and real recordings of the W6IWI beacon):

| Test | CHEBYSHEV (best settings) | DEEPRTTY |
|---|---|---|
| Synthetic, −9 dB | 63.7 % | **0 %** |
| Synthetic, −11 dB | ~85 % | **1.8 %** |
| Real QSB recording (14086 kHz) — wrong characters | 62 | **17** |
| Real QRN recording (7086 kHz) — perfect transmissions | 36 | **45** |

### Zero-beat (tap to tune the radio)

In CW, the FTX-1 shows your transmit frequency, and a correspondent exactly on it is heard at your **CW pitch** (menu KEY PITCH, read with `KP;`). A signal heard at audio tone *T* is therefore (*T* − pitch) Hz away. When you tap it, the app:

1. reads the current mode, pitch and frequency from the radio (`MD0;`, `KP;`, `FA;`);
2. moves VFO-A by (*T* − pitch) Hz — up in CW-U, down in CW-L — so the correspondent lands on your pitch and you transmit on their frequency;
3. sends **`ZI0;`** (the radio's CW auto zero-in) for the final fine adjustment;
4. moves the decoder's band to the pitch; in PILEUP, all station markers shift by the same amount, keeping their text and the red selection.

## Building from source

Requirements:

- **Android Studio** (recent version) with the Android SDK 35.
- **NDK** and **CMake** — the HamNoise noise reduction is native C code (`app/src/main/cpp`).
- A JDK for Gradle — the build is configured for a **JDK 21** daemon (`gradle/gradle-daemon-jvm.properties`), which Gradle can download automatically; the one bundled with a recent Android Studio also works.

```bash
git clone https://github.com/Eugen68/Yaesu-FTX1-CAT-Control.git
cd Yaesu-FTX1-CAT-Control
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Install it with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or run the project from Android Studio.

The neural models are already in `app/src/main/assets` (`model_en_2026-10.onnx`, `model_en_narrow_2026-10.onnx`, `model_rtty_2026-10.onnx`); no download step is needed.

## Tests

```bash
./gradlew testDebugUnitTest
```

The unit tests run on the PC (ONNX Runtime for the JVM is a test-only dependency):

- **RttyDemodulatorTest** — synthetic RTTY (continuous-phase AFSK) with white noise, mistuning, baud-rate error, selective Rayleigh fading, manual typing and type-ahead bursts; regression limits for the decision filter, ATC and the character flywheel.
- **DeepRttyTest** — the full DEEPRTTY chain (front end → streaming → model → ITA2) in NORM and REV.
- **CwModelTest** — the CW model with the app's spectrogram: weak signals, high speed, character time positions, the pileup narrow model and station detection (with hum and unkeyed carriers).
- **BaudotCodecTest**, **CwTextRepairTest**, **OverlapMergerTest**.

## Project structure

```
app/src/main/java/com/example/yaesucatcontroladv/
├── MainActivity.java            side menu, USB, CAT dispatch
├── RadioManager.java            CAT and DATA serial ports (usb-serial-for-android)
├── StareRadio.java              last known frequency, mode and CW pitch
├── EcranComenzi.java            COMMANDS
├── EcranSetari.java             STATION SETTINGS
├── EcranRttyDroid.java          RTTY CENTER
├── RttyDemodulator.java         classic RTTY demodulator (Chebyshev / MF, ATC, flywheel)
├── BaudotCodec.java             ITA2 / US-TTY
├── deeprtty/                    DEEPRTTY front end, spectrogram, streaming, receiver
├── EcranCwDeepDecoder.java      CW AI DECODER
├── deepcw/                      CW: audio, spectrogram, CTC, models, pileup, denoiser
├── EcranCwTrainer.java          CW TRAINER (+ cwtraining/)
├── EcranLogger.java, EcranStatisticiLog.java, AdifExporter.java, QsoDbHelper.java
├── EcranCluster.java, ClusterService.java, ClusterManager.java
├── EcranPskReporter.java, PskReporterManager.java
├── EcranSetariProprii.java      MY SETTINGS, backup, local file server
└── CallsignDetector.java        clickable callsigns in the terminals
app/src/main/cpp/hamnoise/       HamNoise neural noise reduction (C) + JNI bridges
app/src/main/assets/             ONNX models, prefix database, QSO phrases
```

(Some class names are in Romanian — the author's language; all on-screen text is in English.)

## Credits

This app builds on the open-source work of **e04**, used under the MIT License:

- **[DeepCW / web-deep-cw-decoder](https://github.com/e04/web-deep-cw-decoder)** ([deepcw.cc](https://deepcw.cc/)) — the CW models and the decoding pipeline ported to Kotlin.
- **[DeepRTTY](https://github.com/e04/DeepRTTY)** — the RTTY model, its receiver and the overlapping-window streaming method.
- **[HamNoise](https://github.com/e04/HamNoise)** — the neural noise reduction for CW and voice.

Libraries: [ONNX Runtime](https://onnxruntime.ai/) (MIT), [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (MIT), [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (BSD-3-Clause), AndroidX / Material Components / Kotlin coroutines (Apache 2.0).

## License

MIT — see [LICENSE](LICENSE), which also contains the notices of the third-party components.

## Disclaimer

This is amateur-radio software provided "as is". You are responsible for every transmission made with it and for operating within your licence and band plan. It is not affiliated with or endorsed by Yaesu Musen Co., Ltd.; "Yaesu" and "FTX-1" are trademarks of their owner.
