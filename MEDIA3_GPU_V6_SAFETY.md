# HybridVideoEditor V6 — Safe Complex/Dynamic FFmpeg Fallback

V6 is based directly on the V5 Universal Command Engine.

## Why V6 does not claim 100% zero-copy

The Gemini proposal suggested replacing the remaining FFmpeg mux stage with a Media3 audio sequence and implementing arbitrary FFmpeg `sin(t)` crop expressions as a custom shader. Those are not safe drop-in changes for the current Media3 graph and would require a larger, API-verified media pipeline. V6 therefore does **not** claim a universal zero-copy architecture.

The immediate crash fix is more important: unsupported/dynamic `filter_complex` graphs must not be routed into Android's fragile `h264_mediacodec` FFmpeg wrapper merely because the GPU parser cannot represent them.

## V6 behavior

```text
FFmpeg command
    |
    +--> Media3-compatible graph -----------------> Media3/OpenGL/MediaCodec
    |
    +--> complex/dynamic/unsupported graph -------> FFmpegKit + libx264 safe fallback
    |
    +--> simple unsupported graph -----------------> FFmpegKit hardware path (when allowed)
```

### Safety policy

V6 deliberately prefers the CPU encoder for FFmpeg fallback when the command contains:

- `-filter_complex`
- dynamic expressions such as `sin(...)`, `cos(...)`, `if(...)`, etc.
- overlay graphs
- `hwupload` / `hwdownload`
- OpenCL/Vulkan filter requests

This prevents a parser fallback from accidentally selecting `h264_mediacodec` on devices where the hardware encoder is unstable with the requested graph.

### Asset resolution

`amovie=` and `movie=` paths can now resolve from:

- app files directory
- app cache directory
- app external-files directory
- `/storage/emulated/0/Bypass`
- `/storage/emulated/0`
- packaged Android assets

Unknown paths are left untouched so FFmpeg can produce the real diagnostic instead of preprocessing throwing an exception.

### Logging

The existing V5 log panel remains selectable. Native fallback diagnostics include the exact command and the last FFmpeg log section on failure.
