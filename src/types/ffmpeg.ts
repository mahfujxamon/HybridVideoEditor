export interface RenderProgress {
  sessionId: number;
  frame: number;
  fps: number;
  speed: number;
  time: number;
  bitrate: number;
  size: number;
}

export interface RenderResult {
  success: boolean;
  sessionId?: number;
  outputUri?: string;
  normalizedCommand?: string;
  requestedEncoder?: string;
  actualEncoder?: string;
  hardwareEncoderUsed?: boolean;
  processingBackend?: string;
  durationMs?: number;
  errorType?: string;
  errorMessage?: string;
  ffmpegLog?: string;
}

export interface MediaInfo {
  format: string;
  duration: string;
  bitrate: string;
  size: string;
  width?: number;
  height?: number;
  codec?: string;
  fps?: string;
}

export interface CommandEntry {
  id: string;
  name: string;
  command: string;
  category: string;
}