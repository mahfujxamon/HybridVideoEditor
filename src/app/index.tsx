import React, { useState } from 'react';
import {
  SafeAreaView,
  View,
  Text,
  TextInput,
  Pressable,
  StyleSheet,
  ScrollView,
  Alert,
  Image,
  Platform,
  PermissionsAndroid,
} from 'react-native';
import * as ImagePicker from 'expo-image-picker';

import HybridFfmpeg from '../../modules/hybrid-ffmpeg/src/HybridFfmpegModule';

type MediaType = 'video' | 'image' | null;

type HardwareCapabilities = {
  ffmpegAvailable?: boolean;
  gpuExtensionsAvailable?: boolean;
  gpuExtensions?: string;
  gpuDetection?: string;
  mediaCodecH264Available?: boolean;
  universalCommandEngine?: boolean;
};

type OpenClResult = {
  status?: string;
  deviceCreationSuccess?: boolean;
  openclCompileEnabled?: boolean;
  openclFilterCount?: number;
  openclFilters?: string[];
  openclTests?: Record<string, any>;
  deviceOutput?: string;
  ffmpegVersion?: string;
  buildConfiguration?: string;
  error?: string;
};

type AndroidOpenClCandidate = {
  library?: string;
  loaded?: boolean;
  openclSymbols?: boolean;
  status?: string;
  error?: string;
  platformQueryResult?: number;
  platformCount?: number;
  platforms?: Array<{
    name?: string;
    vendor?: string;
    version?: string;
    deviceCount?: number;
    devices?: Array<{
      name?: string;
      vendor?: string;
      version?: string;
      driver?: string;
    }>;
  }>;
};

type AndroidOpenClResult = {
  success?: boolean;
  status?: string;
  library?: string;
  platformCount?: number;
  candidateCount?: number;
  candidates?: AndroidOpenClCandidate[];
  anyLibraryLoaded?: boolean;
  anyOpenClSymbols?: boolean;
  nativeStatus?: string;
  nativeResult?: string;
  error?: string;
};

type OpenGlResult = {
  success?: boolean;
  status?: string;
  error?: string;
  shaderError?: string;
  vendor?: string;
  renderer?: string;
  glVersion?: string;
  glslVersion?: string;
  eglMajor?: number;
  eglMinor?: number;
  contextVersionRequested?: number;
  es3ConfigAvailable?: boolean;
  shaderCompile?: boolean;
  shaderLink?: boolean;
  gpuRenderTest?: boolean;
  glError?: number;
  pixel?: {
    r?: number;
    g?: number;
    b?: number;
    a?: number;
  };
};

export default function HomeScreen() {
  const [selectedUri, setSelectedUri] =
    useState<string | null>(null);

  const [fileName, setFileName] =
    useState<string | null>(null);

  const [mediaType, setMediaType] =
    useState<MediaType>(null);

  const [command, setCommand] = useState(
    '-c:v libx264 -preset ultrafast -crf 28 -c:a aac -b:a 128k'
  );

  const [isRendering, setIsRendering] =
    useState(false);

  const [isHardwareTesting, setIsHardwareTesting] =
    useState(false);

  const [isOpenClTesting, setIsOpenClTesting] =
    useState(false);

  const [isAndroidOpenClTesting, setIsAndroidOpenClTesting] =
    useState(false);

  const [isOpenGlTesting, setIsOpenGlTesting] =
    useState(false);

  const [isOpenGlVideoTesting, setIsOpenGlVideoTesting] =
    useState(false);

  const [log, setLog] = useState(
    'Engine ready...\nWaiting for command...'
  );

  const [capabilities, setCapabilities] =
    useState<HardwareCapabilities | null>(null);

  const [isCheckingHardware, setIsCheckingHardware] =
    useState(false);

  /*
   * ============================================================
   * HARDWARE CAPABILITY CHECK
   * ============================================================
   */
  const checkHardware = async () => {
    try {
      setIsCheckingHardware(true);

      setLog(
        'Checking hardware capabilities...\n\n' +
        'Please wait...'
      );

      const result =
        await HybridFfmpeg.getHardwareCapabilities();

      setCapabilities(result);

      const ffmpegStatus =
        result.ffmpegAvailable
          ? 'AVAILABLE'
          : 'NOT AVAILABLE';

      const mediaCodecStatus =
        result.mediaCodecH264Available
          ? 'AVC ENCODER EXPOSED'
          : 'NOT FOUND';

      const gpuStatus =
        result.gpuDetection === 'COMMAND_DETECTION'
          ? 'READY FOR TEST'
          : result.gpuDetection ?? 'NOT TESTED';

      setLog(
        '================================\n' +
        'HARDWARE CAPABILITY CHECK\n' +
        '================================\n\n' +
        `FFmpeg: ${ffmpegStatus}\n` +
        `GPU/OpenCL: ${gpuStatus}\n` +
        `MediaCodec H.264/AVC: ${mediaCodecStatus}\n` +
        `Universal Engine: ${
          result.universalCommandEngine
            ? 'AVAILABLE'
            : 'NOT DETECTED'
        }\n\n` +
        'Next:\n' +
        '1. Test Android OpenGL ES GPU\n' +
        '2. Test Android OpenCL Runtime\n' +
        '3. Test FFmpeg OpenCL / GPU\n' +
        '4. Test MediaCodec H.264'
      );

    } catch (error) {

      const message =
        error instanceof Error
          ? error.message
          : String(error);

      setLog(
        'Hardware capability check FAILED\n\n' +
        message
      );

      Alert.alert(
        'Hardware Check Error',
        message
      );

    } finally {
      setIsCheckingHardware(false);
    }
  };

  /*
   * ============================================================
   * ANDROID OPENCL RUNTIME TEST
   *
   * This tests the native C++ bridge directly.
   *
   * It does NOT test FFmpeg.
   *
   * It answers:
   * Can the Android APP PROCESS load libOpenCL.so
   * and see an actual OpenCL platform/device?
   * ============================================================
   */
  const testOpenGlRuntime = async () => {
    try {
      setIsOpenGlTesting(true);

      setLog(
        '================================\n' +
        'ANDROID OPENGL ES GPU TEST\n' +
        '================================\n\n' +
        'Using Android public OpenGL ES + EGL APIs.\n\n' +
        'This test will:\n' +
        '1. Create a real EGL GPU context\n' +
        '2. Compile vertex + fragment shaders\n' +
        '3. Render a triangle on the GPU\n' +
        '4. Read back one rendered pixel\n\n' +
        'Please wait...'
      );

      const nativeModule = HybridFfmpeg as any;

      if (typeof nativeModule.checkOpenGlRuntime !== 'function') {
        throw new Error(
          'checkOpenGlRuntime() is not available.\n\n' +
          'Rebuild the APK with the latest native module.'
        );
      }

      const result =
        (await nativeModule.checkOpenGlRuntime()) as OpenGlResult;

      const working =
        result?.success === true &&
        result?.gpuRenderTest === true &&
        result?.shaderCompile === true &&
        result?.shaderLink === true;

      let diagnosticText =
        '================================\n' +
        (working
          ? 'ANDROID OPENGL ES GPU WORKING'
          : 'ANDROID OPENGL ES GPU TEST FAILED') +
        '\n================================\n\n' +
        `Status:\n${result?.status ?? 'UNKNOWN'}\n\n` +
        `EGL Version:\n${result?.eglMajor ?? '?'}.${result?.eglMinor ?? '?'}\n\n` +
        `Context Requested:\n${result?.contextVersionRequested ?? '?'}\n\n` +
        `ES3 Config:\n${result?.es3ConfigAvailable ? 'AVAILABLE' : 'NO'}\n\n` +
        `GL Vendor:\n${result?.vendor ?? 'UNKNOWN'}\n\n` +
        `GL Renderer:\n${result?.renderer ?? 'UNKNOWN'}\n\n` +
        `GL Version:\n${result?.glVersion ?? 'UNKNOWN'}\n\n` +
        `GLSL Version:\n${result?.glslVersion ?? 'UNKNOWN'}\n\n` +
        `Shader Compile:\n${result?.shaderCompile ? 'PASS' : 'FAIL'}\n\n` +
        `Shader Link:\n${result?.shaderLink ? 'PASS' : 'FAIL'}\n\n` +
        `GPU Render Test:\n${result?.gpuRenderTest ? 'PASS' : 'FAIL'}\n\n`;

      if (result?.pixel) {
        diagnosticText +=
          'Readback Pixel:\n' +
          `R=${result.pixel.r ?? '?'} ` +
          `G=${result.pixel.g ?? '?'} ` +
          `B=${result.pixel.b ?? '?'} ` +
          `A=${result.pixel.a ?? '?'}\n\n`;
      }

      diagnosticText +=
        '================================\n' +
        'INTERPRETATION\n' +
        '================================\n\n';

      if (working) {
        diagnosticText +=
          'The app process can access Android OpenGL ES.\n' +
          'A GPU shader was compiled, linked and executed.\n\n' +
          'This is a real GPU-runtime pass, not just a library-load test.\n\n' +
          'Next engineering step: use OpenGL ES only for effects that have a real GPU implementation, while unsupported FFmpeg filters remain on CPU and MediaCodec handles H.264 encoding.';
      } else {
        diagnosticText +=
          'OpenGL ES GPU execution was not proven by this test.\n\n' +
          'Check the status, renderer and error fields above.';
      }

      if (result?.error) {
        diagnosticText += `\n\nError:\n${result.error}`;
      }

      if (result?.shaderError) {
        diagnosticText += `\n\nShader Error:\n${result.shaderError}`;
      }

      if (result?.glError !== undefined) {
        diagnosticText += `\n\nGL Error Code:\n${result.glError}`;
      }

      setLog(diagnosticText);

      Alert.alert(
        working
          ? 'OpenGL ES GPU Working'
          : 'OpenGL ES GPU Diagnostic',
        result?.status ?? 'UNKNOWN'
      );

    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : String(error);

      setLog(
        '================================\n' +
        'OPENGL ES GPU RUNTIME EXCEPTION\n' +
        '================================\n\n' +
        message
      );

      Alert.alert(
        'OpenGL ES Error',
        message
      );
    } finally {
      setIsOpenGlTesting(false);
    }
  };

  const testAndroidOpenClRuntime = async () => {

    try {

      setIsAndroidOpenClTesting(true);

      setLog(
        '================================\n' +
        'ANDROID OPENCL RUNTIME TEST\n' +
        '================================\n\n' +
        'Testing the Android APP process directly.\n\n' +
        'Checking vendor OpenCL library access,\n' +
        'OpenCL symbols, platforms and GPU devices.\n\n' +
        'Please wait...'
      );

      const nativeModule =
        HybridFfmpeg as any;

      if (
        typeof nativeModule.checkAndroidOpenClRuntime !==
        'function'
      ) {
        throw new Error(
          'checkAndroidOpenClRuntime() is not available.\n\n' +
          'Rebuild the APK with the latest native module.'
        );
      }

      const result =
        (await nativeModule.checkAndroidOpenClRuntime()) as AndroidOpenClResult;

      if (result?.success === false) {
        throw new Error(
          result.error ??
          'Native OpenCL diagnostic failed.'
        );
      }

      const candidates =
        Array.isArray(result?.candidates)
          ? result.candidates
          : [];

      const workingCandidate =
        candidates.find(
          candidate =>
            candidate.status === 'OPENCL_WORKING' &&
            candidate.platformCount !== undefined &&
            candidate.platformCount > 0
        );

      const anyLoaded =
        result?.anyLibraryLoaded === true ||
        candidates.some(
          candidate => candidate.loaded === true
        );

      const anySymbols =
        result?.anyOpenClSymbols === true ||
        candidates.some(
          candidate => candidate.openclSymbols === true
        );

      const working =
        !!workingCandidate;

      const status =
        result?.status ??
        (
          working
            ? 'OPENCL_WORKING'
            : !anyLoaded
            ? 'OPENCL_LIBRARY_LOAD_BLOCKED'
            : !anySymbols
            ? 'OPENCL_SYMBOLS_NOT_FOUND'
            : 'OPENCL_NO_PLATFORM'
        );

      let diagnosticText =
        '================================\n' +
        (
          working
            ? 'ANDROID OPENCL WORKING'
            : 'ANDROID OPENCL NOT WORKING'
        ) +
        '\n================================\n\n' +

        `Overall Status:\n${status}\n\n` +

        `Selected Library:\n${
          result?.library ??
          'NONE'
        }\n\n` +

        `Platform Count:\n${
          result?.platformCount ??
          0
        }\n\n` +

        `Libraries Tested:\n${
          result?.candidateCount ??
          candidates.length
        }\n\n`;

      if (candidates.length === 0) {

        diagnosticText +=
          'No candidate library result was returned.\n\n' +
          'Native bridge returned no candidates.';

      } else {

        diagnosticText +=
          '================================\n' +
          'VENDOR LIBRARY DIAGNOSTICS\n' +
          '================================\n\n';

        candidates.forEach(
          (candidate, index) => {

            diagnosticText +=
              `${index + 1}. ${
                candidate.library ??
                'UNKNOWN LIBRARY'
              }\n` +

              `   Loaded: ${
                candidate.loaded
                  ? 'YES'
                  : 'NO'
              }\n` +

              `   OpenCL Symbols: ${
                candidate.openclSymbols
                  ? 'YES'
                  : 'NO'
              }\n` +

              `   Status: ${
                candidate.status ??
                'UNKNOWN'
              }\n`;

            if (
              candidate.platformQueryResult !==
              undefined
            ) {
              diagnosticText +=
                `   Platform Query: ${
                  candidate.platformQueryResult
                }\n`;
            }

            diagnosticText +=
              `   Platforms: ${
                candidate.platformCount ??
                0
              }\n`;

            if (candidate.error) {
              diagnosticText +=
                `   Error: ${
                  candidate.error
                }\n`;
            }

            if (
              Array.isArray(
                candidate.platforms
              )
            ) {

              candidate.platforms.forEach(
                (platform, platformIndex) => {

                  diagnosticText +=
                    `\n   Platform ${
                      platformIndex + 1
                    }\n` +
                    `   Name: ${
                      platform.name ??
                      'UNKNOWN'
                    }\n` +
                    `   Vendor: ${
                      platform.vendor ??
                      'UNKNOWN'
                    }\n` +
                    `   Version: ${
                      platform.version ??
                      'UNKNOWN'
                    }\n` +
                    `   Devices: ${
                      platform.deviceCount ??
                      0
                    }\n`;

                  if (
                    Array.isArray(
                      platform.devices
                    )
                  ) {

                    platform.devices.forEach(
                      (device, deviceIndex) => {

                        diagnosticText +=
                          `\n      Device ${
                            deviceIndex + 1
                          }\n` +
                          `      Name: ${
                            device.name ??
                            'UNKNOWN'
                          }\n` +
                          `      Vendor: ${
                            device.vendor ??
                            'UNKNOWN'
                          }\n` +
                          `      Version: ${
                            device.version ??
                            'UNKNOWN'
                          }\n` +

                          (
                            device.driver
                              ? `      Driver: ${
                                  device.driver
                                }\n`
                              : ''
                          );
                      }
                    );
                  }
                }
              );
            }

            diagnosticText += '\n';
          }
        );
      }

      diagnosticText +=
        '\n================================\n' +
        'DIAGNOSTIC INTERPRETATION\n' +
        '================================\n\n';

      if (working) {

        diagnosticText +=
          'OpenCL library is accessible from the app process.\n' +
          'An OpenCL platform/device was detected.\n\n' +
          'Next step: test FFmpeg OpenCL filter execution.';

      } else if (!anyLoaded) {

        diagnosticText +=
          'The Android app process could not load any tested vendor OpenCL library.\n\n' +
          'This is a linker-namespace/vendor-library access problem, not proof that the phone has no GPU.\n\n' +
          'Do NOT package a proprietary vendor .so into the APK.';

      } else if (!anySymbols) {

        diagnosticText +=
          'A candidate native library loaded, but the required OpenCL entry points were not found.\n\n' +
          'That library should not be treated as an OpenCL implementation.';

      } else {

        diagnosticText +=
          'An OpenCL implementation was reachable, but it reported no usable platform/device to this app process.\n\n' +
          'FFmpeg OpenCL cannot be enabled safely on this runtime yet.';
      }

      setLog(diagnosticText);

      if (working) {

        Alert.alert(
          'Android OpenCL Working',
          `Library: ${
            result?.library ??
            'UNKNOWN'
          }\nPlatforms: ${
            result?.platformCount ??
            0
          }`
        );

      } else {

        Alert.alert(
          'Android OpenCL Diagnostic',
          status
        );
      }

    } catch (error) {

      const message =
        error instanceof Error
          ? error.message
          : String(error);

      setLog(
        '================================\n' +
        'ANDROID OPENCL RUNTIME EXCEPTION\n' +
        '================================\n\n' +
        message
      );

      Alert.alert(
        'Android OpenCL Error',
        message
      );

    } finally {

      setIsAndroidOpenClTesting(false);

    }
  };

  /*
   * ============================================================
   * FFMPEG OPENCL / GPU DIAGNOSTIC
   * ============================================================
   */
  const testOpenCl = async () => {

    try {

      setIsOpenClTesting(true);

      setLog(
        '================================\n' +
        'FFMPEG OPENCL / GPU DIAGNOSTIC STARTING\n' +
        '================================\n\n' +
        'Checking:\n' +
        '• FFmpeg OpenCL build support\n' +
        '• OpenCL filters\n' +
        '• OpenCL device creation\n' +
        '• Actual OpenCL filter execution\n\n' +
        'Please wait...'
      );

      const nativeModule =
        HybridFfmpeg as any;

      if (
        typeof nativeModule.checkOpenCl !==
        'function'
      ) {
        throw new Error(
          'checkOpenCl() is not available in the installed native module. Rebuild the APK with the latest HybridFfmpegModule.kt.'
        );
      }

      const result =
        (await nativeModule.checkOpenCl()) as OpenClResult;

      const status =
        result?.status ??
        'UNKNOWN';

      const compileEnabled =
        result?.openclCompileEnabled === true;

      const deviceSuccess =
        result?.deviceCreationSuccess === true;

      const filterCount =
        result?.openclFilterCount ?? 0;

      const filters =
        Array.isArray(result?.openclFilters)
          ? result.openclFilters
          : [];

      let filterTestText = '';

      if (
        result?.openclTests &&
        typeof result.openclTests === 'object'
      ) {

        const tests =
          Object.entries(
            result.openclTests
          );

        for (const [name, value] of tests) {

          if (
            value &&
            typeof value === 'object'
          ) {

            const testValue =
              value as Record<string, any>;

            const testSuccess =
              testValue.success === true ||
              testValue.returnCodeSuccess === true;

            filterTestText +=
              `${name}: ${
                testSuccess
                  ? 'SUCCESS'
                  : 'FAILED'
              }\n`;

            if (testValue.error) {
              filterTestText +=
                `  Error: ${testValue.error}\n`;
            }

            if (testValue.output) {
              filterTestText +=
                `  Output:\n${testValue.output}\n`;
            }

          } else {

            filterTestText +=
              `${name}: ${String(value)}\n`;
          }
        }
      }

      const working =
        status === 'OPENCL_WORKING';

      const overall =
        working
          ? 'FFMPEG OPENCL / GPU WORKING'
          : 'FFMPEG OPENCL / GPU NOT WORKING';

      setLog(
        '================================\n' +
        `${overall}\n` +
        '================================\n\n' +

        `Status:\n${status}\n\n` +

        `FFmpeg OpenCL Compile:\n${
          compileEnabled
            ? 'YES'
            : 'NO / UNKNOWN'
        }\n\n` +

        `OpenCL Filters:\n${filterCount} detected\n` +

        (
          filters.length > 0
            ? `${filters.join('\n')}\n\n`
            : '\n'
        ) +

        `Device Creation:\n${
          deviceSuccess
            ? 'SUCCESS'
            : 'FAILED'
        }\n\n` +

        (
          filterTestText
            ? `Actual OpenCL Filter Tests:\n${filterTestText}\n`
            : 'Actual OpenCL Filter Tests:\nNo test result returned.\n\n'
        ) +

        (
          result.ffmpegVersion
            ? `FFmpeg Version:\n${result.ffmpegVersion}\n\n`
            : ''
        ) +

        (
          result.deviceOutput
            ? `Device / FFmpeg Diagnostic:\n${result.deviceOutput}\n\n`
            : ''
        ) +

        (
          result.buildConfiguration
            ? `Build Configuration:\n${result.buildConfiguration}\n`
            : ''
        )
      );

      if (working) {

        Alert.alert(
          'FFmpeg OpenCL Working',
          'FFmpeg successfully created an OpenCL device and the OpenCL diagnostic passed.'
        );

      } else {

        let shortMessage =
          `Status: ${status}`;

        if (!compileEnabled) {
          shortMessage +=
            '\n\nFFmpeg build may not contain OpenCL support.';
        } else if (!deviceSuccess) {
          shortMessage +=
            '\n\nOpenCL filters may exist, but device creation failed.';
        }

        Alert.alert(
          'FFmpeg OpenCL Diagnostic',
          shortMessage
        );
      }

    } catch (error) {

      const message =
        error instanceof Error
          ? error.message
          : String(error);

      setLog(
        '================================\n' +
        'FFMPEG OPENCL DIAGNOSTIC EXCEPTION\n' +
        '================================\n\n' +
        message
      );

      Alert.alert(
        'FFmpeg OpenCL Diagnostic Error',
        message
      );

    } finally {

      setIsOpenClTesting(false);

    }
  };

  /*
   * ============================================================
   * SELECT VIDEO / PHOTO (OPTIONAL)
   * ============================================================
   */
  const selectMedia = async () => {

    const permission =
      await ImagePicker.requestMediaLibraryPermissionsAsync();

    if (!permission.granted) {

      Alert.alert(
        'Permission Required',
        'Gallery access is required to select a video or photo.'
      );

      return;
    }

    const result =
      await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['videos', 'images'],
        allowsEditing: false,
        quality: 1,
      });

    if (
      result.canceled ||
      !result.assets?.length
    ) {
      return;
    }

    const asset =
      result.assets[0];

    setSelectedUri(asset.uri);

    setFileName(
      asset.fileName ?? 'Selected media'
    );

    if (asset.type === 'video') {
      setMediaType('video');
    } else {
      setMediaType('image');
    }

    setLog(
      'Engine ready...\n' +
      `Selected: ${
        asset.fileName ??
        'Selected media'
      }`
    );
  };

  /*
   * ============================================================
   * MEDIACODEC H.264 TEST
   * ============================================================
   */
  const testHardwareEncoder = async () => {

    if (!selectedUri) {

      Alert.alert(
        'No Video Selected',
        'Please select a video first.'
      );

      return;
    }

    if (mediaType !== 'video') {

      Alert.alert(
        'Video Required',
        'Please select a video for the hardware encoder test.'
      );

      return;
    }

    try {

      setIsHardwareTesting(true);

      setLog(
        'Hardware H.264 encoder test starting...\n\n' +
        `Input: ${
          fileName ??
          'Selected video'
        }\n\n` +
        'Encoder: h264_mediacodec\n' +
        'Android MediaCodec\n\n' +
        'Testing actual hardware encoding...'
      );

      const result =
        await HybridFfmpeg.testHardwareEncoder(
          selectedUri
        );

      setLog(
        '================================\n' +
        'HARDWARE ENCODER TEST SUCCESS\n' +
        '================================\n\n' +
        `Encoder: ${
          result.hardwareEncoder
        }\n\n` +
        `Output:\n${
          result.outputPath
        }\n\n` +
        `Command:\n${
          result.command
        }\n\n` +
        `${
          result.message
        }`
      );

      Alert.alert(
        'Hardware Test Successful',
        'Android MediaCodec successfully created an H.264 output video.'
      );

    } catch (error) {

      const message =
        error instanceof Error
          ? error.message
          : String(error);

      setLog(
        '================================\n' +
        'HARDWARE ENCODER TEST FAILED\n' +
        '================================\n\n' +
        message
      );

      Alert.alert(
        'Hardware Encoder Error',
        message
      );

    } finally {

      setIsHardwareTesting(false);

    }
  };

  /*
   * ============================================================
   * UNIVERSAL FFMPEG RENDER
   * ============================================================
   */
  const ensureVideoLibraryAccess = async () => {
    if (Platform.OS !== 'android') return true;

    const permission =
      Platform.Version >= 33
        ? PermissionsAndroid.PERMISSIONS.READ_MEDIA_VIDEO
        : PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE;

    if (await PermissionsAndroid.check(permission)) return true;

    const result = await PermissionsAndroid.request(permission, {
      title: 'Video access required',
      message: 'Allow HybridVideoEditor to read videos so commands can render without manually selecting a video.',
      buttonPositive: 'Allow',
      buttonNegative: 'Deny',
    });

    return result === PermissionsAndroid.RESULTS.GRANTED;
  };

  const renderVideo = async () => {

    if (!command.trim()) {

      Alert.alert(
        'Command Required',
        'Please enter FFmpeg arguments.'
      );

      return;
    }

    try {

      const hasAccess = await ensureVideoLibraryAccess();
      if (!hasAccess) {
        Alert.alert('Video Access Needed', 'Allow video access once, then RUN the command again.');
        return;
      }

      setIsRendering(true);

      setLog(
        'FFmpeg starting...\n' +
        `Input: ${
          fileName ??
          'Automatic: newest video from device storage'
        }\n\n` +
        `Arguments:\n${command}\n\n` +
        'Rendering...'
      );

      const result =
        await HybridFfmpeg.renderTestVideo(
          selectedUri && mediaType === 'video'
            ? selectedUri
            : '',
          command
        );

      setLog(
        '================================\n' +
        'FFmpeg RENDER SUCCESS\n' +
        '================================\n\n' +

        `Input:\n${
          result.inputName ??
          fileName ??
          'Automatic video'
        }\n\n` +

        `Output:\n${
          result.outputUri ||
          result.outputPath
        }\n\n` +

        `Command:\n${
          result.command
        }\n\n` +

        (
          result.originalCommand
            ? `Original:\n${
                result.originalCommand
              }\n\n`
            : ''
        ) +

        (
          (result.videoEncoder || result.hardwareEncoder)
            ? `Encoder:\n${
                result.videoEncoder ??
                result.hardwareEncoder
              }\n\n`
            : ''
        ) +

        (
          result.videoBackend
            ? `Video Backend:\n${result.videoBackend}\n\n`
            : ''
        ) +

        (
          result.audioBackend
            ? `Audio Backend:\n${result.audioBackend}\n\n`
            : ''
        ) +

        (
          result.gpuPipeline
            ? `GPU Pipeline:\n${result.gpuPipeline}\n\n`
            : ''
        ) +

        (
          result.hybridMode
            ? `Mode:\n${
                result.hybridMode
              }\n\n`
            : ''
        ) +

        (
          result.openclFilters
            ? `OpenCL Filters:\n${
                JSON.stringify(
                  result.openclFilters
                )
              }\n\n`
            : ''
        ) +

        `${
          result.message ??
          ''
        }`
      );

      Alert.alert(
        'Render Complete',
        'FFmpeg successfully created the output video.'
      );

    } catch (error) {

      const message =
        error instanceof Error
          ? error.message
          : String(error);

      setLog(
        '================================\n' +
        'FFmpeg FAILED\n' +
        '================================\n\n' +
        message
      );

      Alert.alert(
        'FFmpeg Error',
        message
      );

    } finally {

      setIsRendering(false);

    }
  };

  const testOpenGlVideoFrame = async () => {
    if (!selectedUri || mediaType !== 'video') {
      Alert.alert(
        'Select a video first',
        'Choose a video, then run the GPU video-frame test.'
      );
      return;
    }

    try {
      setIsOpenGlVideoTesting(true);
      setLog(
        '================================\n' +
        'OPENGL ES VIDEO FRAME GPU TEST\n' +
        '================================\n\n' +
        '1. FFmpeg decodes one video frame\n' +
        '2. Frame uploads to OpenGL ES\n' +
        '3. GPU shader processes the frame\n' +
        '4. Processed frame is read back\n' +
        '5. FFmpeg encodes the result\n\n' +
        'Please wait...'
      );

      const result = await (HybridFfmpeg as any).testOpenGlVideoFrame(
        selectedUri
      );

      let text =
        '================================\n' +
        'OPENGL ES VIDEO FRAME GPU TEST\n' +
        '================================\n\n' +
        `Status: ${result?.status ?? 'UNKNOWN'}\n\n` +
        `Success: ${result?.success ? 'YES' : 'NO'}\n` +
        `GPU Vendor: ${result?.gpuVendor ?? 'UNKNOWN'}\n` +
        `GPU Renderer: ${result?.gpuRenderer ?? 'UNKNOWN'}\n` +
        `OpenGL ES: ${result?.glVersion ?? 'UNKNOWN'}\n` +
        `Frame: ${result?.width ?? 0} x ${result?.height ?? 0}\n` +
        `Shader: ${result?.shaderOperation ?? 'UNKNOWN'}\n` +
        `Input RGBA: ${result?.inputRgbaBytes ?? 0} bytes\n` +
        `Output RGBA: ${result?.outputRgbaBytes ?? 0} bytes\n`;

      if (result?.outputImagePath) {
        text += `\nGPU output image:\n${result.outputImagePath}\n`;
      }
      if (result?.message) text += `\n${result.message}\n`;
      if (result?.error) text += `\nERROR:\n${result.error}\n`;
      if (result?.ffmpegLog) text += `\nFFmpeg log:\n${result.ffmpegLog}\n`;

      setLog(text);
      Alert.alert(
        result?.success ? 'GPU Video Frame Working' : 'GPU Video Frame Test Failed',
        result?.status ?? 'UNKNOWN'
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setLog(
        '================================\n' +
        'OPENGL ES VIDEO FRAME EXCEPTION\n' +
        '================================\n\n' +
        message
      );
      Alert.alert('GPU Video Frame Error', message);
    } finally {
      setIsOpenGlVideoTesting(false);
    }
  };

  const busy =
    isRendering ||
    isHardwareTesting ||
    isOpenClTesting ||
    isAndroidOpenClTesting ||
    isOpenGlTesting ||
    isOpenGlVideoTesting ||
    isCheckingHardware;

  return (
    <SafeAreaView style={styles.container}>

      <ScrollView
        contentContainerStyle={styles.content}
      >

        <Text style={styles.title}>
          Hybrid Video Editor
        </Text>

        <Text style={styles.subtitle}>
          CPU + GPU Hybrid FFmpeg Engine
        </Text>

        {/* =====================================================
            HARDWARE ENGINE
            ===================================================== */}

        <View style={styles.hardwareCard}>

          <View style={styles.hardwareHeader}>

            <Text style={styles.hardwareTitle}>
              Hardware Engine
            </Text>

            <View
              style={[
                styles.statusDot,
                capabilities &&
                  styles.statusDotReady,
              ]}
            />

          </View>

          <Text style={styles.hardwareText}>
            {capabilities
              ? 'Capability information detected'
              : 'Hardware capability not checked yet'}
          </Text>

          {capabilities && (

            <View style={styles.capabilityList}>

              <Text style={styles.capabilityText}>
                FFmpeg:{' '}
                {capabilities.ffmpegAvailable
                  ? 'AVAILABLE'
                  : 'NOT AVAILABLE'}
              </Text>

              <Text style={styles.capabilityText}>
                GPU/OpenCL:{' '}
                {capabilities.gpuDetection ??
                  'NOT TESTED'}
              </Text>

              <Text style={styles.capabilityText}>
                MediaCodec AVC:{' '}
                {capabilities.mediaCodecH264Available
                  ? 'AVAILABLE'
                  : 'NOT FOUND'}
              </Text>

              <Text style={styles.capabilityText}>
                Universal Engine:{' '}
                {capabilities.universalCommandEngine
                  ? 'AVAILABLE'
                  : 'NOT DETECTED'}
              </Text>

            </View>

          )}

          {/* Hardware capability */}

          <Pressable
            style={[
              styles.hardwareButton,
              busy &&
                styles.buttonDisabled,
            ]}
            onPress={checkHardware}
            disabled={busy}
          >

            <Text style={styles.hardwareButtonText}>
              {isCheckingHardware
                ? 'CHECKING...'
                : 'CHECK HARDWARE'}
            </Text>

          </Pressable>

          {/* =================================================
              ANDROID NATIVE OPENCL
              ================================================= */}

          <Pressable
            style={[
              styles.androidOpenClButton,
              busy &&
                styles.buttonDisabled,
            ]}
            onPress={testAndroidOpenClRuntime}
            disabled={busy}
          >

            <Text style={styles.androidOpenClButtonText}>
              {isAndroidOpenClTesting
                ? 'TESTING ANDROID OPENCL...'
                : 'TEST ANDROID OPENCL RUNTIME'}
            </Text>

          </Pressable>

          {/* =================================================
              ANDROID OPENGL ES
              ================================================= */}

          <Pressable
            style={[
              styles.openGlButton,
              busy &&
                styles.buttonDisabled,
            ]}
            onPress={testOpenGlRuntime}
            disabled={busy}
          >

            <Text style={styles.openGlButtonText}>
              {isOpenGlTesting
                ? 'TESTING OPENGL ES GPU...'
                : 'TEST ANDROID OPENGL ES GPU'}
            </Text>

          </Pressable>

          {/* =================================================
              REAL GPU VIDEO FRAME PIPELINE TEST
              ================================================= */}

          <Pressable
            style={[
              styles.openGlButton,
              busy && styles.buttonDisabled,
            ]}
            onPress={testOpenGlVideoFrame}
            disabled={busy || mediaType !== 'video'}
          >

            <Text style={styles.openGlButtonText}>
              {isOpenGlVideoTesting
                ? 'TESTING GPU VIDEO FRAME...'
                : 'TEST GPU VIDEO FRAME'}
            </Text>

          </Pressable>

          {/* =================================================
              FFMPEG OPENCL
              ================================================= */}

          <Pressable
            style={[
              styles.openClButton,
              busy &&
                styles.buttonDisabled,
            ]}
            onPress={testOpenCl}
            disabled={busy}
          >

            <Text style={styles.openClButtonText}>
              {isOpenClTesting
                ? 'TESTING FFMPEG OPENCL...'
                : 'TEST FFMPEG OPENCL / GPU'}
            </Text>

          </Pressable>

          {/* =================================================
              MEDIACODEC
              ================================================= */}

          <Pressable
            style={[
              styles.encoderButton,
              busy &&
                styles.buttonDisabled,
            ]}
            onPress={testHardwareEncoder}
            disabled={busy}
          >

            <Text style={styles.encoderButtonText}>
              {isHardwareTesting
                ? 'TESTING HARDWARE...'
                : 'TEST HARDWARE ENCODER'}
            </Text>

          </Pressable>

        </View>

        {/* =====================================================
            PREVIEW
            ===================================================== */}

        <View style={styles.preview}>

          {selectedUri ? (

            mediaType === 'image' ? (

              <Image
                source={{
                  uri: selectedUri,
                }}
                style={styles.previewMedia}
                resizeMode="contain"
              />

            ) : (

              <View
                style={
                  styles.videoPlaceholder
                }
              >

                <Text style={styles.videoIcon}>
                  ▶
                </Text>

                <Text
                  style={styles.selectedText}
                >
                  VIDEO SELECTED
                </Text>

              </View>

            )

          ) : (

            <>

              <Text
                style={styles.previewText}
              >
                VIDEO PREVIEW
              </Text>

              <Text
                style={styles.previewSubtext}
              >
                No video selected — RUN can auto-use the newest device video
              </Text>

            </>

          )}

        </View>

        {/* File name */}

        {fileName && (

          <Text
            style={styles.fileName}
            numberOfLines={1}
          >
            {fileName}
          </Text>

        )}

        {/* Select */}

        <Pressable
          style={[
            styles.selectButton,
            busy &&
              styles.buttonDisabled,
          ]}
          onPress={selectMedia}
          disabled={busy}
        >

          <Text style={styles.buttonText}>
            SELECT VIDEO / PHOTO
          </Text>

        </Pressable>

        {/* =====================================================
            COMMAND
            ===================================================== */}

        <Text style={styles.label}>
          FFmpeg Command
        </Text>

        <TextInput
          style={styles.commandBox}
          multiline
          value={command}
          onChangeText={setCommand}
          placeholder="Enter FFmpeg arguments..."
          placeholderTextColor="#777"
          textAlignVertical="top"
          autoCapitalize="none"
          autoCorrect={false}
          editable={!busy}
        />

        {/* RUN */}

        <Pressable
          style={[
            styles.runButton,
            busy &&
              styles.runButtonDisabled,
          ]}
          onPress={renderVideo}
          disabled={busy}
        >

          <Text style={styles.runText}>
            {isRendering
              ? 'RENDERING...'
              : 'RUN'}
          </Text>

        </Pressable>

        {/* =====================================================
            PROGRESS
            ===================================================== */}

        <View
          style={styles.progressContainer}
        >

          <Text style={styles.label}>
            Progress
          </Text>

          <View
            style={
              styles.progressBackground
            }
          >

            <View
              style={[
                styles.progressFill,
                busy
                  ? styles.progressRendering
                  : styles.progressIdle,
              ]}
            />

          </View>

          <Text
            style={styles.progressText}
          >

            {isAndroidOpenClTesting
              ? 'Testing Android OpenCL runtime...'
              : isOpenGlTesting
              ? 'Testing Android OpenGL ES GPU...'
              : isOpenClTesting
              ? 'Testing FFmpeg OpenCL / GPU...'
              : isHardwareTesting
              ? 'Testing MediaCodec hardware encoder...'
              : isRendering
              ? 'Rendering...'
              : isCheckingHardware
              ? 'Checking hardware...'
              : 'Ready'}

          </Text>

        </View>

        {/* =====================================================
            LOG
            ===================================================== */}

        <View style={styles.logHeader}>
          <Text style={styles.label}>
            FFmpeg / Hardware Log
          </Text>
          <Text style={styles.copyHint}>
            Long-press → Select / Copy
          </Text>
        </View>

        <View style={styles.logBox}>

          <ScrollView
            style={styles.logScroll}
            nestedScrollEnabled={true}
            scrollEnabled={true}
            showsVerticalScrollIndicator={true}
            keyboardShouldPersistTaps="always"
            contentContainerStyle={styles.logContent}
          >

            <Text
              style={styles.logText}
              selectable={true}
            >
              {log}
            </Text>

          </ScrollView>

        </View>

      </ScrollView>

    </SafeAreaView>
  );
}

const styles = StyleSheet.create({

  container: {
    flex: 1,
    backgroundColor: '#050505',
  },

  content: {
    padding: 20,
    paddingBottom: 40,
  },

  title: {
    fontSize: 28,
    fontWeight: '800',
    color: '#ffffff',
    marginTop: 10,
  },

  subtitle: {
    color: '#888888',
    fontSize: 14,
    marginTop: 5,
    marginBottom: 20,
  },

  hardwareCard: {
    backgroundColor: '#101010',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#292929',
    padding: 14,
    marginBottom: 16,
  },

  hardwareHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },

  hardwareTitle: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: '800',
  },

  statusDot: {
    width: 9,
    height: 9,
    borderRadius: 5,
    backgroundColor: '#777777',
  },

  statusDotReady: {
    backgroundColor: '#ffffff',
  },

  hardwareText: {
    color: '#888888',
    fontSize: 12,
    marginTop: 6,
  },

  capabilityList: {
    marginTop: 10,
  },

  capabilityText: {
    color: '#bbbbbb',
    fontSize: 12,
    marginTop: 4,
  },

  hardwareButton: {
    height: 42,
    marginTop: 12,
    borderRadius: 9,
    backgroundColor: '#202020',
    alignItems: 'center',
    justifyContent: 'center',
  },

  hardwareButtonText: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '800',
  },

  androidOpenClButton: {
    height: 46,
    marginTop: 8,
    borderRadius: 9,
    backgroundColor: '#181818',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#777777',
  },

  androidOpenClButtonText: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '900',
  },

  openGlButton: {
    height: 46,
    marginTop: 8,
    borderRadius: 9,
    backgroundColor: '#252525',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#888888',
  },

  openGlButtonText: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '900',
  },

  openClButton: {
    height: 46,
    marginTop: 8,
    borderRadius: 9,
    backgroundColor: '#303030',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: '#555555',
  },

  openClButtonText: {
    color: '#ffffff',
    fontSize: 12,
    fontWeight: '900',
  },

  encoderButton: {
    height: 46,
    marginTop: 8,
    borderRadius: 9,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    justifyContent: 'center',
  },

  encoderButtonText: {
    color: '#000000',
    fontSize: 12,
    fontWeight: '900',
  },

  buttonDisabled: {
    opacity: 0.5,
  },

  preview: {
    height: 210,
    backgroundColor: '#111111',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: '#252525',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },

  previewMedia: {
    width: '100%',
    height: '100%',
  },

  videoPlaceholder: {
    alignItems: 'center',
    justifyContent: 'center',
  },

  videoIcon: {
    color: '#ffffff',
    fontSize: 40,
    marginBottom: 8,
  },

  selectedText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: '700',
  },

  previewText: {
    color: '#777777',
    fontSize: 16,
    fontWeight: '700',
  },

  previewSubtext: {
    color: '#555555',
    marginTop: 8,
  },

  fileName: {
    color: '#aaaaaa',
    fontSize: 12,
    marginTop: 8,
  },

  selectButton: {
    marginTop: 14,
    height: 50,
    borderRadius: 10,
    backgroundColor: '#202020',
    alignItems: 'center',
    justifyContent: 'center',
  },

  buttonText: {
    color: '#ffffff',
    fontWeight: '700',
  },

  label: {
    color: '#dddddd',
    fontSize: 15,
    fontWeight: '700',
    marginTop: 20,
    marginBottom: 8,
  },

  commandBox: {
    minHeight: 150,
    backgroundColor: '#101010',
    borderWidth: 1,
    borderColor: '#292929',
    borderRadius: 12,
    padding: 14,
    color: '#ffffff',
    fontSize: 13,
  },

  runButton: {
    marginTop: 14,
    height: 54,
    borderRadius: 12,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    justifyContent: 'center',
  },

  runButtonDisabled: {
    opacity: 0.5,
  },

  runText: {
    color: '#000000',
    fontSize: 17,
    fontWeight: '900',
  },

  progressContainer: {
    marginTop: 4,
  },

  progressBackground: {
    height: 8,
    backgroundColor: '#202020',
    borderRadius: 8,
    overflow: 'hidden',
  },

  progressFill: {
    width: '100%',
    height: '100%',
  },

  progressIdle: {
    opacity: 0.15,
  },

  progressRendering: {
    opacity: 0.7,
  },

  progressText: {
    color: '#888888',
    marginTop: 6,
    fontSize: 12,
  },

  logHeader: {
    marginTop: 4,
  },

  copyHint: {
    color: '#999',
    fontSize: 12,
    marginBottom: 6,
  },

  logBox: {
    height: 380,
    minHeight: 220,
    maxHeight: 450,
    backgroundColor: '#080808',
    borderWidth: 1,
    borderColor: '#222222',
    borderRadius: 12,
    padding: 14,
  },

  logScroll: {
    flex: 1,
  },

  logContent: {
    flexGrow: 1,
    paddingBottom: 24,
  },

  logText: {
    color: '#8f8f8f',
    fontSize: 12,
    lineHeight: 18,
  },

});