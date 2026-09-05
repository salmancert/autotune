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

Three things, in order of how much they matter.

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
| after | −24.3 LUFS | −24.0 LUFS | **0.2 dB** |

Dialogue came up 9.8 dB, music came down 9.0 dB. Both directions matter: turning
everything down would have "fixed" the gap without making the dialogue audible.

## Platform reality

Android does not let an app read another app's audio just because it would be
useful. This is the single most important thing to understand before installing.

| Analysis source | Sees | Needs | Works with |
|---|---|---|---|
| **Playback capture** | Per-app audio | `RECORD_AUDIO` + a MediaProjection grant, Android 10+ | YouTube, browsers, VLC, Kodi, Plex, most players |
| **Output mix** | Everything, including DRM | `CAPTURE_AUDIO_OUTPUT` — a signature permission | Only a privileged/system install (rooted box, custom ROM) |
| **Microphone** | The room | `RECORD_AUDIO`, a device with a mic | Anything, at the cost of room acoustics |
| *(none)* | — | — | Fixed dialogue preset, still running |

Netflix, Prime Video and Disney+ set `ALLOW_CAPTURE_BY_NONE` on their audio. No app
can capture it — that is the platform enforcing their choice, not a bug to work
around.

**But the output processing still applies to them.** The compressor, the dialogue
EQ and the limiter attach to the global output mix, so on a locked-down streaming
app Autotune runs its fixed dialogue-forward preset: the multiband compressor pulls
loud low-frequency content down and the presence band keeps consonants up. That is
static rather than adaptive — good, not as good. To get the adaptive path on those
apps you need either a device with a microphone (enable the fallback in settings) or
a privileged install.

## Install

Build the APK:

```bash
./gradlew :app:assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
- **Night mode** — tighter ceiling, more boost, faster recovery. For watching
  without waking the house.
- **Use microphone as fallback** — off by default. Turn it on if you mostly watch
  apps that block capture and your device has a microphone. Autotune subtracts its
  own applied gain back out of what the mic hears, so the loop does not chase
  itself, but the room still colours the measurement.

Per-app profiles adjust these automatically: films on Netflix/Prime get the most
correction (widest native range), YouTube the least (already loudness-normalised
upstream), local files in VLC/Kodi/Plex the most aggressive settings (untouched
masters).

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
frame accuracy 0.97, clip accuracy 0.97   (unseen seed, 120 clips)
             SPEECH    MUSIC  EFFECTS
SPEECH         3658      102        0
MUSIC           144     3565       51
EFFECTS           0       47     3713
```

The corpus deliberately includes the cases that break naive detectors: dialogue
mixed only 3 dB over a score, sung vocals (a voice, but musical), sparse solo piano
with speech-like gaps, applause, helicopter rotors and alarm beeps (periodic without
being music), band-limited broadcast speech, and playback-chain damage — speaker EQ
tilt, broadcast compression, noise floors, clipping.

Retrain and rewrite the weights file:

```bash
./gradlew :model-training:trainModel          # ~30 s, prints held-out accuracy
./gradlew :model-training:trainModel --args="--clips=60 --epochs=15 --dry-run"
```

**Swapping in a bigger model.** `AudioClassifier` is a two-method interface. A
TensorFlow Lite model (YAMNet or similar) can be dropped in behind it without the
engine changing, at the cost of APK size and CPU.

## Repository layout

| Module | What it is | Android? |
|---|---|---|
| `audio-core` | FFT, biquads, BS.1770 loudness, features, model inference, the engine | No — plain Kotlin/JVM |
| `model-training` | Corpus synthesis, the trainer, model-quality tests | No |
| `app` | Capture, effects, service, boot receiver, TV UI | Yes |

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
- Model accuracy is measured on synthetic audio.
