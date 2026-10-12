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

`VideoPlayerCache` is the engine-neutral persistence seam. The controller owns restore/save timing and uses `VideoSource.mediaId` as the stable key. Engine adapters only receive the resolved source and start position, so engine switching cannot fork progress state.

## Engine switching

1. Read the current position and playback intent from the unified state.
2. Release the old engine and cancel its state collector.
3. Create the selected adapter and reattach the existing `Surface`.
4. Restore volume and playback speed.
5. Prepare the same source at the captured position and restore play/pause intent.

The old adapter cannot publish stale callbacks after replacement because the controller checks engine identity before accepting an emitted state.

## Cache boundary

`AppPrivateVideoPlayerCache` is the bundled adapter. It stores progress records separately from progressive network byte ranges. Local sources and HLS/DASH manifests participate in progress persistence but bypass content caching. Before an engine is prepared, a supported network source is resolved to a loopback HTTP URL owned by one process-local `ProgressiveMediaProxy`; Media3 and IjkPlayer therefore consume the same cached bytes.

The proxy binds only to `127.0.0.1` and uses an unguessable per-process path token. It divides upstream content into fixed 512 KiB segments under `filesDir/video-mobile-player-cache/<sha256(mediaId)>`. A cached segment is served from disk. A missing segment is requested upstream with HTTP Range, streamed to the player while it is written to a `.part` file, then promoted to `.segment` after its length is verified. Seeking naturally requests and stores a different segment. Cache size enforcement is LRU at segment granularity.

`mediaId` is the immutable content identity; URL and authorization headers are transport details and may rotate without changing the cache key. The adapter persists upstream length and validators in `metadata.json`, rejects servers that do not honor byte ranges, and invalidates segment data when a fetched range proves that the upstream identity changed. HLS/DASH segment caching and encrypted/offline license management remain outside the bundled adapter; callers can disable content caching per source or inject another `VideoPlayerCache` adapter without changing the controller or an engine interface.

## Presentation boundary

`PlayerPresentationController` models `INLINE`, `FULLSCREEN`, and `FLOATING` separately from playback. `VideoPlayerHost` is the bundled Compose adapter for the common `INLINE <-> FULLSCREEN` transition: it moves the same controller-backed surface into a fullscreen dialog, requests landscape orientation, manages immersive system bars, and restores the prior orientation on exit. PiP, overlay permission, foreground service, custom containers, and close behavior remain external adapters driven by `PlayerPresentationRequest`.

This request/confirmation seam keeps platform window operations out of the playback controller and supports asynchronous transitions such as permission prompts and PiP callbacks. Business detail pages remain outside the module; they only size `VideoPlayerHost` and issue commands through `VideoPlayerController`.

## Extension point

A new playback SDK only needs a `PlaybackEngine` plus `PlaybackEngineFactory`. The controller accepts a factory map, so adding an engine does not change existing adapters or the state model. The bundled `rememberVideoPlayerController` intentionally registers only Media3 and IjkPlayer.

Network throughput is reported through the engine-neutral `EnginePlaybackState.networkSpeedBytesPerSecond`. Each adapter owns its native measurement strategy; the controller and Compose UI do not depend on Media3 or IjkPlayer networking APIs.

Presentation transitions never own playback state. `PlayerPresentationController` remembers the last non-floating mode, while the shared `VideoPlayerController` keeps the media session alive as surfaces move between inline, fullscreen, PiP, or overlay containers.
