# Architecture

## Dependency boundary

```text
Host application
      |
      v
player-compose ----> player-engine-media3 ----> player-core
       |                                      ^
       +-----------> player-engine-ijk -------+
```

`PlaybackEngine` is the only interface an SDK adapter implements. `VideoPlayerController` owns engine replacement and state continuity. `VideoPlayer` renders only `PlaybackState` and emits controller commands.

## Engine switching

1. Read the current position and playback intent from the unified state.
2. Release the old engine and cancel its state collector.
3. Create the selected adapter and reattach the existing `Surface`.
4. Restore volume and playback speed.
5. Prepare the same source at the captured position and restore play/pause intent.

The old adapter cannot publish stale callbacks after replacement because the controller checks engine identity before accepting an emitted state.

## Presentation boundary

`PlayerPresentationController` models `INLINE`, `FULLSCREEN`, and `FLOATING` separately from playback. It emits a `PlayerPresentationRequest`; the host owns orientation, immersive window flags, PiP, overlay permission, foreground service, and close behavior. The host confirms a successful transition or rejects it while retaining the previous mode.

This request/confirmation boundary keeps platform window operations out of the playback controller and supports asynchronous transitions such as permission prompts and PiP callbacks.

## Extension point

A new playback SDK only needs a `PlaybackEngine` plus `PlaybackEngineFactory`. The controller accepts a factory map, so adding an engine does not change existing adapters or the state model. The bundled `rememberVideoPlayerController` intentionally registers only Media3 and IjkPlayer.

Network throughput is reported through the engine-neutral `EnginePlaybackState.networkSpeedBytesPerSecond`. Each adapter owns its native measurement strategy; the controller and Compose UI do not depend on Media3 or IjkPlayer networking APIs.

Presentation transitions never own playback state. `PlayerPresentationController` remembers the last non-floating mode, while the shared `VideoPlayerController` keeps the media session alive as surfaces move between inline, fullscreen, PiP, or overlay containers.
