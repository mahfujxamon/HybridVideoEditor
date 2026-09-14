# HybridVideoEditor V5 — Universal Command Engine

V5 keeps the successful V4 Media3 Composition/OpenGL ES path and improves the command boundary.

## Changes

- Added `FfmpegCommandTokenizer` for quote-aware FFmpeg argument parsing.
- `-filter_complex` and `-af` extraction now uses tokenized arguments instead of fragile regex-only extraction.
- Input timing (`-ss ... -i ...`) uses the same tokenizer.
- Complex filter expressions containing spaces, `|`, parentheses, quoted `enable=...`, `firequalizer`, `compand`, and `pan` remain one argument.
- GPU parsing remains conservative: unsupported video graphs still fall back to FFmpegKit.
- FFmpeg fallback failures now return the actual command plus the last FFmpeg log in the rejected error, making the app diagnostic log selectable/copyable.
- UI log text is selectable (`selectable={true}`); long-press the log to Select/Copy.

## Backend routing

1. Media3 simple video -> Media3 Transformer + OpenGL ES + MediaCodec.
2. Supported two-input overlay -> Media3 Composition + OpenGL ES + MediaCodec.
3. Media3-compatible video + simple `-af` -> GPU video + FFmpegKit audio + mux.
4. Supported overlay + complex audio -> GPU composition + FFmpegKit audio + mux.
5. Anything unsupported by the GPU IR -> original FFmpegKit fallback.

V5 does not claim arbitrary FFmpeg filters are GPU accelerated. The universal command engine preserves FFmpeg compatibility while routing only supported video graphs to the real Media3/OpenGL path.
