# Architecture

## Ownership

```text
Host / Compose UI
       |
       | immutable StateFlow<PlayerState> + controller commands
       v
PlayerController Interface
       |
       +-- DefaultPlayerController (internal)
       |      +-- media classification and playback intent
       |      +-- engine selection/switching and state continuity
       |      +-- retry policy and unified state mapping
       |      +-- VideoContentCache
       |      +-- PlayerEnvironmentAdapter
       |
       v
PlaybackEngine Interface
       +-- Media3PlaybackEngine
       +-- IjkPlaybackEngine
       +-- third-party Adapter
```

`PlayerController` is the external seam. The UI can read its immutable state and call capabilities; it cannot obtain a `PlaybackEngine`, mutate state, or depend on Media3/Ijk types. `DefaultPlayerController` remains internal so its implementation can change without expanding the public Interface.

## Playback intent

`setVideoUrl(url, headers, autoPlay)` prepares a source. Playback begins only through `play()`:

```text
setVideoUrl(autoPlay = true)
      -> prepare(source)
      -> play()
      -> playWhenReady = true
```

Calling `play()` during preparation records intent in both Controller and Adapter. Calling `pause()` clears it. Consequently `playWhenReady` and `isPlaying` are deliberately separate; buffering is normally `true/false`.

## State model

`PlayerState` groups data by responsibility:

| Group | Contents |
| --- | --- |
| root | engine, phase, play intent, actual playback, source, error/end reason |
| `media` | protocol, VOD/live classification, file/container/bitrate, audio/video info |
| `timeline` | position, nullable duration, buffered position, DVR window and live edge |
| `tracks` | video/audio/subtitle tracks and selection state |
| `subtitleCues` | engine-independent text currently rendered by the selected subtitle track |
| `network` | media transfer speed and loading state |
| `capabilities` | operations currently supported |
| `metrics` | decoder/rendering measurements |
| `environment` | system volume and screen brightness |

Unavailable values are nullable rather than encoded as zero. Convenience properties such as `positionMs` and `durationMs` exist for simple UI rendering without changing the grouped source of truth.

Media protocol and playback type are orthogonal: HLS and DASH may be either VOD or LIVE. DVR is represented by a seekable start/end window plus a live edge instead of pretending every live stream has a conventional duration.

## Engine registration

`PlayerEngineId` is an open value object. A `PlaybackEngineProvider` publishes metadata, performs a fast source-level support check, and creates its Adapter. The Controller owns provider selection and switching.

Switching captures position, playback intent, player volume and speed, releases the old Adapter, attaches the existing Surface to the new Adapter, prepares the same source and restores those values. Stale callbacks are ignored by engine identity.

An unsupported source or explicitly incompatible engine produces a terminal `UNSUPPORTED` error with `RetryPolicy.NEVER`. Network errors may use `BACKOFF`; only retryable errors enable `retry()`.

## Environment controls

Brightness and system media volume are Controller capabilities implemented by `PlayerEnvironmentAdapter`. Player volume remains a `PlaybackEngine` capability. This keeps platform/window operations out of SDK Adapters while preserving the rule that UI only talks to Controller.

## Presentation

The Compose call chain has three distinct modules:

```text
ContainedVideoPlayer / ExpandableVideoPlayer
                    |
                    v
              VideoPlayer
                    |
                    v
             PlayerScaffold
```

`VideoPlayer` is the raw playback host. Its small Interface accepts a Controller, source information, interaction policy and one content slot. Its implementation owns the Surface and host lifecycle, immutable state collection, URL preparation, five-second control visibility state, and generic brightness/volume gesture recognition. It has no knowledge of titles, icons, colors, loading visuals, fullscreen, lock semantics, or a particular control layout.

`PlayerScaffold` is a pure layer assembler used inside the `VideoPlayer` content slot. It orders loading/error state, subtitle and overlay layers, persistent content, and transient top/center/side/bottom controls. A full `controlsContent` replacement has precedence over the positional control slots.

`ContainedVideoPlayer` and `ExpandableVideoPlayer` own the mature presentation: default icons, strings, colors, status views, control visibility rules, title/back behavior, and their layout choices. `ExpandableVideoPlayer` additionally owns fullscreen orientation and lock policy. A `null` presentation slot selects the built-in default; a supplied slot replaces that layer, and an empty lambda deliberately hides it. Custom full controls are responsible for their own locked presentation.

`PlayerInteractionConfig` configures the behavior retained by the raw host independently from presentation. `ContainedVideoPlayer` stays inside the exact bounds supplied by the caller and never changes orientation. `ExpandableVideoPlayer` uses those same caller-owned inline bounds and adds a controlled landscape fullscreen policy. Both render video with a centered fit rule, so source dimensions never resize the component and mismatched aspect ratios use black letterboxing instead of cropping or stretching.

Default controls remain data-driven through `VideoPlayerControlLayout`. Display title and back navigation are presentation inputs, never engine state. Built-in and custom presentation receives immutable `PlayerScope` or `PlayerUiScope` data and Controller-backed actions; playback state remains Controller-owned.

## Cache

`VideoContentCache` is engine-neutral. The bundled cache proxies supported progressive HTTP range requests through loopback storage and is resolved before an Adapter sees the source. HLS/DASH bypass this progressive-file cache. Playback history remains a host concern.

## Future live work

Media3 already reports live classification, seekability and live offset where available. The 1.0.0 Interface intentionally reserves DVR window and live-edge semantics, but future work must still add cross-engine conformance tests, manifest refresh policy, explicit live retry/backoff behavior and live-window UI scenarios before those behaviors are considered complete.
