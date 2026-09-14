import { registerWebModule, NativeModule } from 'expo';

class HybridFfmpegModule extends NativeModule<{}> {}

export default registerWebModule(HybridFfmpegModule, 'HybridFfmpegModule');
