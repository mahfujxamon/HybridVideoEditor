# Media3 GPU migration

This revision makes Jetpack Media3 Transformer the primary video backend for commands that the parser can represent as Media3/OpenGL effects.

## Backend order

1. Media3 Transformer: hardware MediaCodec video decode/encode + OpenGL ES effects.
2. FFmpegKit: universal fallback, including arbitrary/new FFmpeg commands and audio filter commands.

## Currently mapped video filters

- `hflip`
- `vflip`
- `negate`
- numeric `scale=WIDTH:HEIGHT`
- `scale=iw*X:ih*Y`
- numeric `crop=W:H:X:Y`
- numeric `rotate=ANGLE_IN_RADIANS`
- numeric `boxblur`

Complex `-filter_complex`, dynamic expressions, overlay graphs, and audio-filter commands intentionally fall back to FFmpegKit for now. The original command is never discarded.

## Important

The Media3 path does not report GPU merely because a command looks GPU-friendly. A successful Media3 export reports `backend=MEDIA3_TRANSFORMER_OPENGL` and the actual `videoEncoder` from `ExportResult`.

The old `GpuVideoRenderer.kt` is retained in the source tree for now but is no longer selected by `renderTestVideo`.


## V3 hybrid stage
- Media3 Transformer remains the primary video backend.
- Simple `-af` audio filters now use FFmpegKit for audio only, while video is rendered by Media3/OpenGL/MediaCodec.
- Final mux uses FFmpegKit stream-copy for the already encoded video.
- Common `crop=iw*factor:ih*factor` and `scale=iw:ih` expressions are accepted by the video IR.
- Obsolete custom `GpuVideoRenderer.kt` is removed; Media3 is the sole GPU video backend.
- UI now exposes actual video backend, audio backend, encoder and GPU pipeline.
