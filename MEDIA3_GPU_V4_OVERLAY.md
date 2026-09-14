# Media3 GPU V4 — Complex Overlay IR

This stage expands the V3 hybrid pipeline without removing the universal FFmpeg fallback.

## New routing

```text
FFmpeg command
      |
      v
Universal parser
      |
      +-- simple -vf -----------------> Media3 Transformer + OpenGL ES
      |
      +-- simple -af + simple video --> Media3 video + FFmpegKit audio + mux
      |
      +-- supported filter_complex ----> Media3 Composition + OpenGL ES
      |                                      |
      |                                      +-- base video sequence
      |                                      +-- overlay video sequence
      |                                      +-- time-aware alpha/visibility
      |                                      +-- MediaCodec encode
      |
      +-- anything unsupported --------> FFmpegKit universal fallback
```

## V4-supported complex video pattern

The new IR recognizes a common two-input FFmpeg graph:

```text
[base branch][overlay branch]overlay=...:enable=...:x=0:y=0
```

Supported branch effects currently include the V3 linear set:

- scale
- crop using `iw/2`, `ih/2`, `iw/3`, `ih/3`, `iw/1.5`, `ih/1.5`, `iw*X`, `ih*Y`
- hflip / vflip
- negate
- rotate
- boxblur (implemented through Media3 GaussianBlur approximation)

Supported overlay visibility expressions currently include the reference patterns:

```text
gte(mod(t,5),3)
lt(mod(t,7),3.5)*gte(t,3)
```

The compositor uses Media3 `VideoCompositorSettings` and `StaticOverlaySettings` so visibility can change from the presentation timestamp without CPU pixel readback.

## Complex audio

When a supported overlay graph also contains the reference audio graph (`atempo`, `bass`, `volume`, `aecho`, `firequalizer`, `compand`, `pan`, `highpass`, `lowpass`, `amovie`, `amix`, etc.), V4 extracts the audio portion, processes it through FFmpegKit, then stream-copies the Media3-compressed video into the final mux.

## Reference coverage

Against the supplied 96-command reference set, the V4 overlay IR recognizes 26 commands directly (including duplicate/preload variants). The remaining commands continue through the universal FFmpeg path rather than being incorrectly promoted.

The intentionally unsupported groups at this stage are primarily:

- multi-overlay recursive graphs (several chained overlays)
- dynamic crop expressions using `sin(t*...)`
- `setpts` time-warp expressions
- more complex multi-input graph topologies

Those remain candidates for the next IR stage.
