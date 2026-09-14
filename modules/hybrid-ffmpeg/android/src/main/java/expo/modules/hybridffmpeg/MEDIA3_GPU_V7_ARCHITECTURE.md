# V7 GPU/Hybrid Architecture

- DynamicCropEffect evaluates `presentationTimeUs` in a custom Media3 OpenGL effect without CPU pixel readback.
- FFmpeg can still produce complex processed audio as an `.m4a` cache file; Media3 consumes it as an `EditedMediaItemSequence.withAudioFrom(...)` inside the final `Composition`. The old FFmpeg `-c:v copy -c:a copy` final mux step is removed.
- Relative `aud/...` / `movie=` / `amovie=` references are resolved to app-owned paths before FFmpeg fallback.
- FFmpeg fallback is classified with a reason code so the UI/log can explain why Media3 GPU was not used.
- Complex/dynamic graphs are CPU-safe in FFmpeg fallback rather than being forced into `h264_mediacodec`.

This is a hybrid zero-CPU-pixel-readback target. The intermediate `.m4a` created by FFmpeg is still a storage-backed audio stage; it is not a claim of physically zero storage I/O.
