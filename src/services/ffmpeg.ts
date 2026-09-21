import { EventEmitter, NativeModulesProxy } from 'expo-modules-core';
import HybridFfmpegModule from '../../modules/hybrid-ffmpeg/src/HybridFfmpegModule';
import { RenderProgress, RenderResult, MediaInfo } from '../types/ffmpeg';

const emitter = new EventEmitter(HybridFfmpegModule ?? NativeModulesProxy.HybridFfmpeg);

export const FfmpegService = {
  probeMedia: async (uri: string): Promise<MediaInfo> => {
    return await HybridFfmpegModule.probeMedia(uri);
  },
  renderVideo: async (command: string, type: 'DIRECT' | 'WINDOWS', inputUri?: string): Promise<RenderResult> => {
    return await HybridFfmpegModule.renderVideo(command, type, inputUri);
  },
  cancelRender: async (sessionId: number): Promise<boolean> => {
    return await HybridFfmpegModule.cancelRender(sessionId);
  },
  onRenderStarted: (listener: (event: { sessionId: number }) => void) => {
    return emitter.addListener('onRenderStarted', listener);
  },
  onRenderProgress: (listener: (event: RenderProgress) => void) => {
    return emitter.addListener('onRenderProgress', listener);
  },
};