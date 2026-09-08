# Autotune

An Android TV audio stabiliser: it keeps dialogue at a level you can hear and
stops the music between scenes from blowing you off the sofa.

It starts with the TV, runs in the background, and works across YouTube, Netflix,
Prime Video, VLC, Kodi/Plex and browsers — with the caveat, spelled out in
[Platform reality](#platform-reality), that Android limits what an app is allowed
to *listen to*, and that limit differs per app.

```
    ┌──────────┐   analysis audio    ┌─────────────┐   26 features   ┌────────────┐
    │ capture  │ ──── 16 kHz mono ──▶│  features   │────────────────▶│ classifier │
    └──────────┘                     └─────────────┘                 └────────────┘
         ▲                                  │                               │
         │                          BS.1770 loudness              speech / music / effects
    playback capture,                       │                               │
    output mix, or mic                      ▼                               ▼
                                     ┌───────────────────────────────────────────┐
                                     │              stabiliser engine            │
                                     │  levelling · ducking · ceiling backstop   │
                                     └───────────────────────────────────────────┘
                                                        │ gain, EQ, compression
                                                        ▼
                                     ┌───────────────────────────────────────────┐
                                     │ DynamicsProcessing on the output mix      │
                                     │ pre-EQ → multiband compressor → limiter   │
                                     └───────────────────────────────────────────┘
```

## What it actually does

Four things, in order of how much they matter.

**1. It knows what it is listening to.** A small neural network labels every 32 ms
of audio as dialogue, music, or effects. Instantaneous spectra cannot do this — a
held vowel and a held cello note look identical for 30 ms — so the model reads
2-second *temporal* statistics: how often the level drops into a gap, how strongly
the envelope beats at the ~4 Hz syllabic rate of speech versus at a musical pulse,
how the harmonicity and pitch move. See [The model](#the-model).

**2. It levels dialogue and ducks everything else, on different clocks.** Speech is
pulled slowly toward a target loudness, so a mumbled line comes up without the
levelling itself becoming audible. Music and effects are held under a ceiling a few
dB *below* that target, with a fast attack (~40 ms) so a cut to a loud score is
caught immediately, and a slow release so it does not pump.

**3. It does not trust the model.** Whatever the classifier believes, nothing is
allowed more than 2 dB above the dialogue target. A misclassified stinger still
gets neutralised; the model decides how *gracefully* a loud passage is handled, not
*whether* it is handled.

**4. It notices what refuses to get quieter.** The three rules above all react to
*level*. None of them catches an advert break that sits exactly at the ceiling,
brick-walled, for ninety seconds — loud in a way that is fatiguing rather than
startling. A fourth term builds extra attenuation while loudness stays above the
ceiling *and* the content has no dynamics left, and releases the moment either
stops being true. Both conditions are needed: loud alone would punish a dramatic
climax that is meant to be loud. Measured across the corpus, clean dialogue varies
by about 24 dB inside a two-second window, a film score by 2 dB, and brick-walled
advertising audio by under half a dB — which is what makes the second condition a
usable signal rather than a guess.

There is one more piece of judgement in there worth calling out. The obvious way to
measure "how loud is the dialogue" is the standard 3-second short-term loudness —
and it is wrong in exactly the case you care about. For three seconds after a loud
cue it still reads the cue, so the quiet line that follows gets *cut* at the moment
you are leaning in to hear it. Autotune instead keeps a speech-gated estimate,
updated only while speech is actually playing, so it remembers the dialogue level
across the cue and has the right gain ready the instant the talking resumes.

### Measured on a synthetic scene

`EndToEndStabilisationTest` builds quiet dialogue → loud music cue → quiet dialogue
and runs the whole chain over it:

| | dialogue | music | gap |
|---|---|---|---|
| before | −34.1 LUFS | −15.0 LUFS | **19.1 dB** |
| after | −24.4 LUFS | −24.0 LUFS | **0.4 dB** |

Dialogue came up 9.7 dB, music came down 9.0 dB. Both directions matter: turning
everything down would have "fixed" the gap without making the dialogue audible.

## Presets

The three sliders are a fine way to express *how* the stabiliser should behave and
a poor way to ask someone sitting on a sofa. One button on the remote says the same
thing, and the genres genuinely differ:

| Preset | For | What changes |
|---|---|---|
| **Films & drama** | Wide cinematic mixes | Music held 5 dB under dialogue, up to 12 dB of lift |
| **Sport** | Commentary over a crowd | Crowd ducked less (it is atmosphere), faster recovery, harder on ad breaks |
| **Late night** | Not waking the house | Music 8 dB under, nothing above target, up to 15 dB of lift |
| **News & talk** | Already-levelled broadcast | A light touch, consonants pushed forward |
| **Custom** | Your own settings | Nothing — the sliders rule |

A preset decides how hard to work, not how loud you like your television: your
dialogue level and correction strength survive it untouched. Adjusting a value a
preset owns switches you to Custom rather than leaving a slider on screen that
visibly does nothing.

Per-app profiles compose *on top* as relative nudges — Netflix leans the ceiling
1 dB further down, YouTube 1 dB back — so choosing a preset still means something
whatever app is playing. (They used to set absolute values, which silently
overruled the preset; a test caught it.)

## Hearing what it is doing

**Compare with it off**, on the status screen and as a button on the ongoing
notification, is the fastest answer to "is this actually doing anything?". It fades
the gain to unity over 250 ms, then switches the compressor and limiter out too, so
the comparison covers the whole chain rather than flattering it. Press it again to
bring everything back.

## Prior art

Someone pointed me at [Auto Volume Control for TV](https://play.google.com/store/apps/details?id=purpose.company.smartvolumestabilizer)
(`purpose.company.smartvolumestabilizer`) as an app in this space, and later
supplied a copy of it, so what follows is from the artifact rather than from its
store listing. I read its manifest, permissions, declared components, resources
and the Android APIs it references — the observable capability surface. Its
implementation was not decompiled and no code was copied.

**How it solves the DRM problem, which is the interesting part.** It does not
capture app audio at all. Version 0.1.1 declares exactly one foreground service
type — `microphone` (`0x80`) — requests `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`,
and references no `AudioPlaybackCaptureConfiguration` and no `Visualizer`. It
listens to the room, and it acts by moving the TV's own volume:
`setStreamVolume` / `adjustStreamVolume`, with `LoudnessEnhancer` as the only
audio effect. Two `AccessibilityService`s handle remote key bindings; a
`SYSTEM_ALERT_WINDOW` overlay shows the level over the video.

That is why it works with Netflix: a microphone hears everything a speaker plays,
and the volume control affects everything. It confirms rather than contradicts the
conclusion in [Platform reality](#platform-reality) — per-app capture of a
protected stream is not available to anyone.

Its control law is coarser than the one here: the strings describe a default
level, an increase level for quiet scenes and a decrease level for loud ones,
with low/medium/high sensitivity — three volume positions driven by a decibel
meter, rather than a loudness target with speech/music classification. It also
declares no `BOOT_COMPLETED` receiver, so it does not come back on its own after
the TV restarts.

**Correction to what this file said earlier.** A previous version of this section
described genre presets (Movies, Sports, Late Night, News, Custom) taken from a
search summary of the store listing. Those do not appear anywhere in this build's
resources; the closest thing is the three-position sensitivity setting. The
[presets](#presets) here are worth having on their own merits, but they should not
be credited to that app.

**What was actually worth taking:** the insight that an audio effect is not always
enough, and that driving the system volume is the fallback that always does
something. That is implemented here as a third output stage — see below.

## Platform reality

Android does not let an app read another app's audio just because it would be
useful. This is the single most important thing to understand before installing.

**Input** — where the analysis audio comes from:

| Analysis source | Sees | Needs | Works with |
|---|---|---|---|
| **Playback capture** | Per-app audio | `RECORD_AUDIO` + a MediaProjection grant, Android 10+ | YouTube, browsers, VLC, Kodi, Plex, most players |
| **Output mix** | Everything, including DRM | `CAPTURE_AUDIO_OUTPUT` — a signature permission | Only a privileged/system install (rooted box, custom ROM) |
| **Microphone** | The room | `RECORD_AUDIO`, a device with a mic | Anything, at the cost of room acoustics |
| *(none)* | — | — | Fixed dialogue preset, still running |

**Output** — how the correction is applied:

| Output stage | Quality | Fails when |
|---|---|---|
| **DynamicsProcessing** | Per-band compression, limiter, EQ | Not on API < 28, or the device refuses session 0 |
| **LoudnessEnhancer** | Broadband gain around a standing boost | Same, minus the compressor |
| **TV volume** | Coarse steps, visible on screen | The device reports `isVolumeFixed` |

The last row exists because of something worth being blunt about: on a TV that
passes audio through to a soundbar or AV receiver over HDMI, an effect on the
output mix can attach, report success, and be completely inaudible — the mix it
is processing is not the one being decoded. Nothing in the API says so. If
Autotune appears to do nothing on such a setup, turn on **Adjust the TV volume
directly**, which drives the same control the remote does.

That path is coarse (a step is a couple of dB, unevenly spaced) and it moves a
control you also own, so it yields: reach for the remote and your new level
becomes the baseline that corrections ride on, rather than something to argue
with.

Netflix, Prime Video and Disney+ set `ALLOW_CAPTURE_BY_NONE` on their audio. No app
can capture it — that is the platform enforcing their choice, not a bug to work
around.

**But the output processing usually still applies to them.** The compressor, the
dialogue EQ and the limiter attach to the global output mix, so on a locked-down
streaming app Autotune runs its fixed dialogue-forward preset: the multiband compressor pulls
loud low-frequency content down and the presence band keeps consonants up. That is
static rather than adaptive — good, not as good. To get the adaptive path on those
apps you need either a device with a microphone (enable the fallback in settings) or
a privileged install.

## Install

### Easiest: take the APK from CI

Every push builds one. Open the repository's
**Actions** tab, click the most recent green *CI* run, and download the
**autotune-debug-apk** artifact at the bottom of the page. Unzip it and you have
`app-debug.apk` — no toolchain to install at all. (Artifacts need you to be signed
in to GitHub, and expire after 90 days. To build one on demand without pushing,
use *Actions → CI → Run workflow*.)

### Build it yourself on Linux

One script, which installs the Android SDK for you if you do not have one:

```bash
tools/build-apk.sh                        # just build
tools/build-apk.sh --install 192.168.1.50 # build, connect to the TV, install, launch
```

It needs **Java 17 or 21** (`sudo apt install openjdk-17-jdk`), `curl` and
`unzip`, and puts the SDK under `~/Android/Sdk` — nothing system-wide, no root.
The first run downloads a few hundred MB of SDK; later runs take seconds. If
several JDKs are installed it finds a supported one and uses it, whatever `java`
on your `PATH` happens to be.

**A newer JDK will not do.** The Android Gradle Plugin 8.7.3 targets 17 and
Kotlin 2.0.21 cannot emit for JVM targets newer than itself, so on Java 24+ the
build fails with a bare version number for an error message. `settings.gradle.kts`
checks for this and explains it rather than letting that happen.

Doing it by hand comes to the same thing:

```bash
# 0. Java 17 or 21 - NOT newer (see above)
sudo apt install openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# 1. SDK (skip if you already have one; just set ANDROID_HOME)
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME=~/Android/Sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 2. Build
cd /path/to/autotune
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` (~2 MB). It is signed
with the standard debug key, which is fine for sideloading onto your own TV.

### Getting it onto the TV

1. On the TV: **Settings → Device Preferences → About**, scroll to the bottom
   and press OK seven times on the build row. **The row is not always called
   "Build"** — that is the usual reason people cannot find it:

   | TV | Row to press seven times |
   |---|---|
   | TCL, and most Android TV 11 | **Android TV OS build** |
   | Sony, Nvidia Shield, Xiaomi | **Build** |
   | Google TV skin (Settings → System → About) | **Android TV OS build** |

2. Then **Settings → Device Preferences → Developer options → USB debugging** →
   on. That is enough on its own; it opens ADB on port 5555 and no separate
   "network debugging" toggle is needed on most sets.
3. Find the TV's address under **Settings → Network & Internet → your network →
   IP address**.
4. From the Linux box — the TV shows an authorisation prompt on the first
   connection, and you have to accept it there:

```bash
adb connect 192.168.1.50:5555      # accept the prompt that appears on the TV
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If Autotune does not appear in the TV's app row, launch it directly:

```bash
adb shell monkey -p dev.autotune.tv -c android.intent.category.LEANBACK_LAUNCHER 1
```

Common snags: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means an older copy signed with
a different key is installed — `adb uninstall dev.autotune.tv` first.
`device unauthorized` means the confirmation dialog on the TV has not been accepted
yet. `adb: no devices` after a TV reboot just needs `adb connect` again.

To watch it work:

```bash
adb logcat -s StabilizerService:* PlaybackCapture:* DynamicsProcessor:* OutputMixSource:*
```

Then open Autotune from the TV's app row and work down the screen:

1. **Grant audio capture** — asks for the microphone permission, then the capture
   consent dialog. Android issues a fresh capture token per grant and will not
   persist it, so this has to be repeated after each reboot; the ongoing
   notification is a shortcut back here.
2. **Grant app detection** — opens the notification-access screen. This is the only
   supported way for an app to see which app is playing, which is what selects the
   per-app profile. Optional; without it everything uses the generic profile.
   Grantable over adb instead:
   ```bash
   adb shell cmd notification allow_listener dev.autotune.tv/dev.autotune.tv.session.PlaybackNotificationListener
   ```
3. Leave **Start when the TV starts** on.

### Privileged install (optional, for Netflix and friends)

On a rooted box or a custom ROM, installing to `/system/priv-app` grants
`CAPTURE_AUDIO_OUTPUT`, which unlocks output-mix analysis for every app including
DRM-protected ones:

```bash
adb root && adb remount
adb push app-debug.apk /system/priv-app/Autotune/Autotune.apk
adb shell chmod 644 /system/priv-app/Autotune/Autotune.apk
adb reboot
```

## Tuning it for your room

Everything is on the main screen; left/right on the remote adjusts a value.

- **Dialogue level** (default −20 LUFS) — where speech should sit. *If dialogue is
  still too quiet, raise this first.*
- **Keep music below dialogue by** (default 4 dB) — how far under dialogue loud
  music is held. Raise it if music still feels overbearing; lower it if the score
  sounds flattened.
- **Correction strength** (default 80%) — the master amount. Lower it if the sound
  feels squashed or you can hear the levelling working.
- **Watching** — the preset (see [Presets](#presets)). The one control most people
  will ever touch.
- **Compare with it off** — fades the whole chain out so you can hear the
  difference, and back in when you press it again.
- **Adjust the TV volume directly** — off by default. Turn it on if the sound is
  unchanged with everything else configured, which usually means HDMI passthrough
  to external speakers.
- **Use microphone as fallback** — off by default. Turn it on if you mostly watch
  apps that block capture and your device has a microphone. Autotune subtracts its
  own applied gain back out of what the mic hears, so the loop does not chase
  itself, but the room still colours the measurement.

Per-app profiles nudge these automatically on top of the preset: films on
Netflix/Prime get a little more correction (widest native range), YouTube a little
less (already loudness-normalised upstream), local files in VLC/Kodi/Plex the most
(untouched masters).

## The model

A 26 → 24 → 16 → 3 MLP, about 11 KB of weights, a few thousand multiply-adds per
32 ms hop. The features do the heavy lifting; the network only has to learn the
decision surface between them. That matters on a TV stick that is also decoding 4K.

It is trained on a **synthetic corpus** generated from a seed — dialogue, score and
effects synthesised from their acoustic structure rather than recorded. That choice
buys three things: the label is exact, anyone can regenerate the committed weights
with one command, and nothing about anyone's viewing has to leave their TV to build
it. It also means the accuracy below is *on synthetic audio*, and should not be read
as a claim about real film mixes:

```
frame accuracy 0.98, clip accuracy 0.99   (unseen seed, 120 clips)
             SPEECH    MUSIC  EFFECTS
SPEECH         3675       24       61
MUSIC            54     3695       11
EFFECTS          30       69     3661
```

The corpus deliberately includes the cases that break naive detectors: dialogue
mixed only 3 dB over a score, sung vocals (a voice, but musical), sparse solo piano
with speech-like gaps, applause, helicopter rotors and alarm beeps (periodic without
being music), band-limited broadcast speech, and playback-chain damage — speaker EQ
tilt, broadcast compression, noise floors, clipping.

It also carries the specific shape of television drama: a **sustained,
percussion-free score bed** holding chords under a scene, and the swelling
scene-transition sting. Serial drama — Pakistani and Indian especially — runs a
score under nearly every scene, and it looks nothing like the drum-driven music a
generic generator produces: no beat for the pulse feature to find, no gaps for the
low-energy feature to find, both layers continuous and harmonic. Without it in the
corpus the model learns "music has drums", which is the wrong lesson for exactly
the content this app is most needed on.

Retrain and rewrite the weights file:

```bash
./gradlew :model-training:trainModel          # ~30 s, prints held-out accuracy
./gradlew :model-training:trainModel --args="--clips=60 --epochs=15 --dry-run"
```

**Swapping in a bigger model.** `AudioClassifier` is a two-method interface. A
TensorFlow Lite model (YAMNet or similar) can be dropped in behind it without the
engine changing, at the cost of APK size and CPU.

## Training on your own channels

The shipped model is trained on synthesis. Real audio from the channels *you*
actually watch will beat it on those channels, and a couple of hours is enough
because the pipeline fine-tunes from the shipped weights rather than starting over.

Everything below runs on your machine — the build never touches the network.

### 1. Get the audio

```bash
tools/fetch-corpus.sh ary   "https://www.youtube.com/@<ARY channel>/videos"   8
tools/fetch-corpus.sh humtv "https://www.youtube.com/@<Hum TV channel>/videos" 8
```

Needs `yt-dlp` and `ffmpeg`. Paste the channel, playlist or episode URL from your
browser — handles change, so check the one you want rather than trusting a string in
a README. The script keeps ten minutes per video and converts to the 16 kHz mono WAV
the pipeline reads; that is all the model ever sees, so nothing richer is worth
storing. Downloading from YouTube is against its Terms of Service, and whether that
matters for a private training set you never redistribute is your call to make.

Already have audio? Skip this entirely. Any WAV works: recordings off an HDMI
capture, your own files, a DVD rip.

### 2. Draft the labels

```bash
./gradlew :model-training:autoLabel --args="data/ary --source=ary"
```

Hand-labelling four hours of drama is work nobody finishes, and a corpus nobody
finishes trains nothing. So the current model drafts it: it writes `labels.csv` with
a row per scene, and `review.csv` holding the 60 segments it was *least* sure about.

Open `review.csv`, listen to those spans, and fix the ones it got wrong in
`labels.csv` — change the label, or delete the row if you cannot tell. That is the
whole job: your attention goes where the information is, not where the model was
already right.

```
# file, start, end, label, source, confidence
ary/ary-abc123.wav, 41.50, 78.00, speech, ary, 0.99
ary/ary-abc123.wav, 79.50, 96.00, music,  ary, 0.58   <- worth a listen
```

Labels are `speech`, `music` or `effects` (aliases: `dialogue`, `ost`, `sfx`, …).
Prefer clean examples: a scene that is *mostly* dialogue with a quiet bed under it is
`speech` — that is exactly what the app has to get right — but a span that genuinely
changes halfway is better deleted than guessed. If you would rather cut clips by
hand, drop them into `data/ary/speech/`, `data/ary/music/`, `data/ary/effects/` and
point `--data` at the folder instead.

### 3. Fine-tune

```bash
./gradlew :model-training:trainModel --args="--data=data/ary/labels.csv --fine-tune"
```

Point `--data` at a manifest or a directory; pass several by concatenating the
manifests. What `--fine-tune` changes:

- it starts from the shipped weights instead of random ones, so two hours of
  television adjusts a boundary the synthetic corpus already found, rather than
  being memorised;
- it keeps that model's feature standardisation, because the warm-started weights
  are only meaningful in the scaling they were trained in;
- it takes smaller steps (lr 0.002) over fewer epochs, so the run does not forget
  synthesis in order to fit one channel.

Real rows count for 4× a synthetic one (`--real-weight=N`), otherwise a small,
precious corpus gets averaged away. The synthetic corpus stays in the mix on
purpose — dropped entirely, the model overfits your episodes and gets worse on
everything else you watch.

The split is **by file, never by segment**: two scenes from one episode share a mix
engineer and a cast, so letting one into training and the other into the held-out
set would measure memorisation and call it accuracy. Held-out files are reported
separately, per source, against what the shipped model scores on the same audio:

```
real held-out (unseen files)
  frame accuracy: 0.91
  bundled model on the same audio: 0.84  (+0.07)
  ary              0.93
  humtv            0.89
```

If that delta is not positive, do not ship the model — the honest outcomes are
"needs more data", "the labels have mistakes in them", or "synthesis was already
good enough here".

### 4. Check it, then install it

```bash
./gradlew :model-training:evaluateModel --args="data/humtv/labels.csv"   # bundled
./gradlew :model-training:evaluateModel --args="data/humtv/labels.csv --model=<new>.model"
```

`trainModel` writes straight into
`audio-core/src/main/resources/dev/autotune/core/ml/speech_music_mlp.model`, so
rebuilding the app ships your model:

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Add `--dry-run` to see the numbers without overwriting the committed weights. To get
back to the shipped model: `git checkout audio-core/src/main/resources`.

### How much audio is enough?

Roughly 30 minutes per class per channel is where fine-tuning starts to beat
synthesis; a couple of hours is comfortable. Coverage matters more than volume —
ten episodes of one drama teach less than two episodes each of five, and the
material worth labelling most is what the model already finds hard: the title song,
a scene where dialogue sits just over the score, a crowded bazaar, a phone call.

## Repository layout

| Module | What it is | Android? |
|---|---|---|
| `audio-core` | FFT, biquads, BS.1770 loudness, features, model inference, the engine | No — plain Kotlin/JVM |
| `model-training` | Corpus synthesis, the trainer, model-quality tests | No |
| `app` | Capture, effects, service, boot receiver, TV UI | Yes |
| `tools/` | `build-apk.sh` and `fetch-corpus.sh`, both run on your own machine | — |

All the signal processing and decision logic lives outside the Android module, so it
is unit-testable on any JVM:

```bash
./gradlew :audio-core:test :model-training:test
```

`settings.gradle.kts` leaves `:app` out of the build entirely when no Android SDK is
configured, which is why the core tests run in CI without one.

## Latency, and why there are two compressors

Analysing sound takes time: capture buffering plus a 64 ms analysis frame puts the
engine's gain roughly 100–200 ms behind the audio. That is fine for "this scene is a
loud music cue, hold it down" and useless for the first transient of a gunshot.

So the work is split. The engine handles the slow judgement. The platform multiband
compressor and limiter run *inside* the audio path with no such delay and catch the
instantaneous peak. Neither alone is enough; together they cover both timescales.

## Privacy

Audio is analysed in RAM and discarded — nothing is recorded, stored or transmitted,
and the app has no network permission at all. The classifier runs entirely on the
device. `PlaybackNotificationListener` exists only because Android requires an
enabled notification listener before `MediaSessionManager` will name the playing app;
it never reads a notification.

## Known limitations

- Capture consent is not persistable across reboots — Android's rule, not a choice.
  After a reboot the adaptive path needs one click unless you are on a privileged
  install.
- Netflix/Prime/Disney+ cannot be analysed on an ordinary install (see above).
- Attaching effects to audio session 0 is how every equaliser app on Android works,
  but it is deprecated and some devices or ROMs refuse it. Autotune falls back to
  `LoudnessEnhancer` and, failing that, reports that output processing is
  unavailable rather than pretending to work.
- The microphone fallback is a genuine closed loop. Applied gain is subtracted back
  out, but reverberant rooms and delay still degrade the estimate.
- Model accuracy is measured on synthetic audio unless you retrain it on your own
  recordings, which is what [Training on your own channels](#training-on-your-own-channels)
  is for.
- Segment boundaries drafted by `autoLabel` are approximate: the classifier turns
  over somewhere inside a transition, so each segment is trimmed half a second at
  both ends before it becomes training data.
