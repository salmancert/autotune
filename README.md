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
| after | −24.4 LUFS | −24.0 LUFS | **0.4 dB** |

Dialogue came up 9.7 dB, music came down 9.0 dB. Both directions matter: turning
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
| `tools/` | `fetch-corpus.sh`, for pulling training audio on your own machine | — |

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
