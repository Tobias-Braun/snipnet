# ADR 0001: Video engine

Status: accepted

## Context

The desktop editor needs one video stack for playback, frame-accurate seeking, timeline thumbnails, the audio
waveform, the proxy transcode (issue: client import/upload) and the export. It has to run on macOS (arm64 and x64),
Windows x64 and Linux x64, play HEVC and H.264 (tripod cameras and phones record both), and be shippable inside an
installer without asking the user to install anything.

## Options

| | JavaCV / FFmpeg (bytedeco) | VLCJ | JavaFX Media |
|---|---|---|---|
| HEVC + H.264 | Yes, all FFmpeg codecs | Yes, but through the installed VLC | H.264 only, HEVC depends on the OS, no MKV |
| Frame-accurate seek | Yes: seek to the previous keyframe, decode forward to the exact frame | No: seeks land near the target, no per-frame access | No exact seek, no frame access without extra tricks |
| Frames as data | Every decoded frame is available, so the Compose UI, thumbnails and the waveform use one code path | Frame callbacks exist, but seeking and timing are VLC's | Only rendered into a JavaFX node, which does not embed cleanly in Compose |
| Transcode / export | The same jar ships the `ffmpeg` executable | Not covered, would still need ffmpeg | Not covered, would still need ffmpeg |
| Bundle size | 25 to 35 MB per platform (see below) | Requires a VLC installation (100+ MB) that the user has to provide | Extra JavaFX modules, about 40 MB per platform |
| License | JavaCV Apache 2.0, FFmpeg natives LGPL, GPL with x264/x265 | LGPL/GPL (VLC) | GPL + Classpath exception |

JavaCV/FFmpeg is the only option that covers all requirements, and it is the one that also provides the transcode and
export tooling later issues need. VLCJ and JavaFX Media were not prototyped: they fail the frame-accuracy and
"nothing to install" criteria on paper, and the spike showed that JavaCV meets the performance criteria comfortably.

## Decision

Use JavaCV (`org.bytedeco:javacv` for `FFmpegFrameGrabber`, plus `javacpp` and `ffmpeg` with per-platform natives)
behind the `VideoEngine` interface in `desktopApp` (`app.snipnet.desktop.video`).

- **Natives.** `ffmpeg` with the `-gpl` classifier (x264/x265 are needed for the proxy transcode and export; the LGPL
  flavour cannot encode H.264). The `-gpl` suffix is selected at runtime by `FfmpegRuntime` through the JavaCPP
  `platform.extension` property. The build resolves only the natives of the machine that runs Gradle; pass
  `-PallNativePlatforms` to pull in macOS arm64/x64, Windows x64 and Linux x64 together, for example on a release
  machine that builds all installers. javacv itself is added without its transitive dependencies (OpenCV, OpenBLAS
  and others), which nothing here uses.
- **Licensing consequence.** With the GPL flavour of FFmpeg in the app, the distributed application has to comply
  with the GPL for that component. This has to be settled before the first public release (options: keep the GPL
  natives, or switch to the LGPL natives and use a platform encoder such as VideoToolbox/Media Foundation for the
  proxy). Only the `ffmpeg` classifier in `desktopApp/build.gradle.kts` would change.
- **Playback.** Two independent demuxers, one for pictures and one for audio, each on its own thread, paced by a
  shared media clock (wall time, scaled by the playback rate). Audio goes to a `javax.sound` `SourceDataLine`.
  A single demuxer would starve the audio buffer whenever the container interleaves audio and video in chunks of
  half a second or more (MP4 does). Audio is only audible at 1x; other rates are silent.
- **Seeking.** `seek(ms, exact = true)` positions on the keyframe before the target and decodes forward until the
  frame at the target is reached (within half a frame). With `exact = false` the frame shown is whatever the demuxer
  yields right after its fast seek, never later than the target.
- **Derived data.** Thumbnails (JPEG) and waveform peaks (raw floats) are cached under
  `<data dir>/cache/media/<hash of path, size, mtime>/`, so replacing or editing a file invalidates its entries.
- **ffmpeg binary.** `ffmpegPath()` returns the `ffmpeg` executable that JavaCPP extracts from the natives jar, for
  the transcode and export issues to run as a subprocess.

## Measurements

Apple M-series laptop (arm64, macOS), JDK 21, JavaCV 1.5.14 with FFmpeg 8.1.2, synthetic 30 fps clips of 10 s
(`testsrc2`, x264/x265 `veryfast`, crf 23, keyframe every 60 frames). Reproduce with
`SNIPNET_BENCHMARK=1 ./gradlew :desktopApp:test --tests '*VideoBenchmark*' -i` in `apps/client`.

| Clip | Decode (fps, frames converted to BGR) | Seek to a random position, exact | Seek, fast |
|---|---|---|---|
| 1080p H.264 | 1682 | 67 ms | 66 ms |
| 4K H.264 | 555 | 191 ms | 192 ms |
| 1080p HEVC | 818 | 76 ms | 74 ms |

Seek latency is measured from `seek()` until the new picture is published, including decode of up to one keyframe
interval, the conversion to a Compose `ImageBitmap` and the publish. Decoding is far faster than real time even
at 4K, so playback is limited by the display clock, not by the decoder. The synthetic clips are easier to decode
than real handheld or tripod footage of a bright outdoor scene, so treat the numbers as an upper bound; the
headroom is more than 10x at 4K. Fast seek is not faster than exact seek in this measurement: with a 2 s keyframe
interval decoding forward from the keyframe is cheap compared with the demux, flush and conversion overhead, so the
editor can use exact seeks for scrubbing too and only fall back to fast ones if long-GOP footage proves otherwise.

Download size of the natives per platform (`ffmpeg` `-gpl` + `javacpp`): macOS arm64 about 23 MB, macOS x64 about
28 MB, Linux x64 about 31 MB, Windows x64 about 37 MB.

## Consequences

- One dependency covers playback, thumbnails, waveform, transcode and export on all three desktop systems.
- Installers are larger by the size of the natives of their platform, and the GPL question above must be answered
  before distribution.
- JavaCV's grabber API decodes on the calling thread; the player owns its threads and callers only use the
  thread-safe `VideoPlayer` methods and flows.
- On Linux the FFmpeg natives link dynamically against PulseAudio, VA-API, VDPAU and a few X11 libraries (packages
  `libpulse0`, `libva2`, `libvdpau1`, ...), which mainstream desktops have. The GitHub runner image does not, so the
  client workflow installs them.
