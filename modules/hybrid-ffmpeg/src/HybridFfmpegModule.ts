import { NativeModule, requireNativeModule } from 'expo';

declare class HybridFfmpegModule extends NativeModule<{}> {}

export default requireNativeModule<HybridFfmpegModule>('HybridFfmpeg');
