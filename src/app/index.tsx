// File: src/app/index.tsx
import React, { useState, useEffect } from 'react';
import { View, Text, TextInput, Pressable, StyleSheet, ScrollView, Alert, KeyboardAvoidingView, Platform } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { useLocalSearchParams } from 'expo-router';

import { FfmpegService } from '../services/ffmpeg';
import { RenderStatusCard } from '../components/RenderStatusCard';
import { RenderProgress, MediaInfo } from '../types/ffmpeg';
import { Colors, Spacing } from '../constants/theme';

export default function EditorScreen() {
  const params = useLocalSearchParams();
  const [cmdType, setCmdType] = useState<'DIRECT' | 'WINDOWS'>('DIRECT');
  const [command, setCommand] = useState('ffmpeg -i input.mp4 -vf hflip -c:v libx264 -preset ultrafast -crf 28 output.mp4');
  
  const [inputUri, setInputUri] = useState<string | null>(null);
  const [mediaInfo, setMediaInfo] = useState<MediaInfo | null>(null);
  
  const [status, setStatus] = useState<'READY' | 'RENDERING' | 'COMPLETED' | 'ERROR' | 'CANCELLED'>('READY');
  const [progress, setProgress] = useState<RenderProgress | null>(null);
  const [sessionId, setSessionId] = useState<number | null>(null);

  const [reqEncoder, setReqEncoder] = useState<string>('');
  const [actEncoder, setActEncoder] = useState<string>('');
  const [hwUsed, setHwUsed] = useState<boolean>(false);

  useEffect(() => {
    if (params.command) {
      setCommand(params.command as string);
      setCmdType(params.type === 'WINDOWS' ? 'WINDOWS' : 'DIRECT');
    }
  }, [params.command]);

  useEffect(() => {
    const subStart = FfmpegService.onRenderStarted((e) => setSessionId(e.sessionId));
    const subProg = FfmpegService.onRenderProgress((e) => setProgress(e));
    return () => { subStart.remove(); subProg.remove(); };
  }, []);

  const pickVideo = async () => {
    const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ImagePicker.MediaTypeOptions.Videos });
    if (!result.canceled && result.assets[0]) {
      const uri = result.assets[0].uri;
      try {
        const info = await FfmpegService.probeMedia(uri);
        setInputUri(info.localUri || uri);
        setMediaInfo(info);
      } catch (e: any) {
        Alert.alert("Probe Failed", e.message || "Could not read video metadata.");
      }
    }
  };

  const handleRender = async () => {
    if (!command.trim()) return Alert.alert("Error", "Command cannot be empty.");
    setStatus('RENDERING');
    setProgress(null);
    setSessionId(null);
    setReqEncoder('');
    setActEncoder('');
    setHwUsed(false);
    
    const result = await FfmpegService.renderVideo(command, cmdType, inputUri || undefined);
    
    if (result.success) {
      setStatus('COMPLETED');
      setReqEncoder(result.requestedEncoder || '');
      setActEncoder(result.actualEncoder || '');
      setHwUsed(result.hardwareEncoderUsed || false);
      Alert.alert("Render Complete", `Output saved to:\n${result.outputUri}\n\nEncoder: ${result.actualEncoder}`);
    } else if (result.errorType === 'CANCELLED') {
      setStatus('CANCELLED');
    } else {
      setStatus('ERROR');
      } else if (result.errorType === 'CANCELLED') {
      setStatus('CANCELLED');
    } else {
      setStatus('ERROR');
      // FIX: Show the actual FFmpeg log so we know exactly why it failed
      const errorLog = result.ffmpegLog ? `\n\nLog:\n${result.ffmpegLog.substring(result.ffmpegLog.length - 500)}` : '';
      Alert.alert("Render Failed", `${result.errorType}\n${result.errorMessage}${errorLog}`);
    }
  };

  const handleCancel = async () => {
    if (sessionId !== null) {
      await FfmpegService.cancelRender(sessionId);
    }
  };

  return (
    <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.headerTitle}>HybridVideoEditor</Text>

        <View style={styles.toggleContainer}>
          <Pressable style={[styles.toggleBtn, cmdType === 'DIRECT' && styles.toggleActive]} onPress={() => setCmdType('DIRECT')}>
            <Text style={[styles.toggleText, cmdType === 'DIRECT' && styles.toggleTextActive]}>Direct FFmpeg</Text>
          </Pressable>
          <Pressable style={[styles.toggleBtn, cmdType === 'WINDOWS' && styles.toggleActive]} onPress={() => setCmdType('WINDOWS')}>
            <Text style={[styles.toggleText, cmdType === 'WINDOWS' && styles.toggleTextActive]}>Windows / Batch</Text>
          </Pressable>
        </View>

        <View style={styles.previewContainer}>
          <View style={styles.placeholder}>
            <Text style={styles.placeholderText}>
              {inputUri ? 'VIDEO SELECTED' : 'NO VIDEO SELECTED'}
            </Text>
          </View>
          <Pressable style={styles.selectBtn} onPress={pickVideo}>
            <Text style={styles.btnText}>{inputUri ? 'CHANGE VIDEO' : 'SELECT INPUT VIDEO'}</Text>
          </Pressable>
          {mediaInfo && (
            <Text style={styles.mediaInfoText}>
              {mediaInfo.width}x{mediaInfo.height} • {mediaInfo.fps} fps • {mediaInfo.codec} • {mediaInfo.duration}s
            </Text>
          )}
        </View>

        <TextInput
          style={styles.commandInput}
          multiline
          value={command}
          onChangeText={setCommand}
          placeholder={cmdType === 'DIRECT' ? 'ffmpeg -i input.mp4 ... output.mp4' : 'for %%t in (...) DO ffmpeg ...'}
          placeholderTextColor={Colors.textSecondary}
          editable={status !== 'RENDERING'}
        />

        <RenderStatusCard 
          progress={progress} 
          status={status} 
          durationStr={mediaInfo?.duration} 
          requestedEncoder={reqEncoder}
          actualEncoder={actEncoder}
          hardwareEncoderUsed={hwUsed}
        />

        {status === 'RENDERING' ? (
          <Pressable style={[styles.actionBtn, { backgroundColor: Colors.accent }]} onPress={handleCancel}>
            <Text style={styles.btnText}>CANCEL RENDER</Text>
          </Pressable>
        ) : (
          <Pressable style={[styles.actionBtn, { backgroundColor: Colors.primary }]} onPress={handleRender}>
            <Text style={styles.btnText}>RENDER VIDEO</Text>
          </Pressable>
        )}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: Colors.background },
  scroll: { padding: Spacing.lg, paddingBottom: 100 },
  headerTitle: { color: Colors.text, fontSize: 24, fontWeight: 'bold', marginBottom: Spacing.lg, marginTop: Spacing.xl },
  toggleContainer: { flexDirection: 'row', backgroundColor: Colors.surfaceElevated, borderRadius: 8, padding: 4, marginBottom: Spacing.md },
  toggleBtn: { flex: 1, paddingVertical: 8, alignItems: 'center', borderRadius: 6 },
  toggleActive: { backgroundColor: Colors.primary },
  toggleText: { color: Colors.textSecondary, fontWeight: 'bold', fontSize: 12 },
  toggleTextActive: { color: Colors.text },
  previewContainer: { backgroundColor: Colors.surface, borderRadius: 12, overflow: 'hidden', marginBottom: Spacing.lg, borderWidth: 1, borderColor: Colors.border },
  placeholder: { width: '100%', height: 160, justifyContent: 'center', alignItems: 'center', backgroundColor: Colors.surfaceElevated },
  placeholderText: { color: Colors.textSecondary, fontWeight: 'bold' },
  selectBtn: { backgroundColor: Colors.surfaceElevated, padding: Spacing.md, alignItems: 'center', borderTopWidth: 1, borderTopColor: Colors.border },
  btnText: { color: Colors.text, fontWeight: 'bold' },
  mediaInfoText: { color: Colors.accent, textAlign: 'center', padding: Spacing.sm, fontSize: 12, fontWeight: 'bold', backgroundColor: Colors.surfaceElevated },
  commandInput: { backgroundColor: Colors.surface, color: Colors.text, padding: Spacing.md, borderRadius: 12, minHeight: 140, textAlignVertical: 'top', fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace', borderWidth: 1, borderColor: Colors.border, marginBottom: Spacing.lg },
  actionBtn: { padding: Spacing.lg, borderRadius: 12, alignItems: 'center', marginTop: Spacing.sm }
});