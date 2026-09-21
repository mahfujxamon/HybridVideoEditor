// File: src/components/RenderStatusCard.tsx
import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Colors, Spacing } from '../constants/theme';
import { RenderProgress } from '../types/ffmpeg';

interface Props {
  progress: RenderProgress | null;
  status: 'READY' | 'RENDERING' | 'COMPLETED' | 'ERROR' | 'CANCELLED';
  durationStr?: string;
  requestedEncoder?: string;
  actualEncoder?: string;
  hardwareEncoderUsed?: boolean;
}

export function RenderStatusCard({ progress, status, durationStr, requestedEncoder, actualEncoder, hardwareEncoderUsed }: Props) {
  const getStatusColor = () => {
    switch (status) {
      case 'RENDERING': return Colors.primary;
      case 'COMPLETED': return Colors.success;
      case 'ERROR': return Colors.error;
      case 'CANCELLED': return Colors.accent;
      default: return Colors.textSecondary;
    }
  };

  let progressPercent = 'N/A';
  if (progress && durationStr) {
    const totalMs = parseFloat(durationStr) * 1000;
    if (totalMs > 0 && progress.time > 0) {
      progressPercent = `${Math.min(100, (progress.time / totalMs) * 100).toFixed(1)}%`;
    }
  }

  const formatTime = (ms: number | undefined | null) => {
    if (ms === undefined || ms === null) return 'N/A';
    const totalSeconds = Math.floor(ms / 1000);
    const m = Math.floor(totalSeconds / 60).toString().padStart(2, '0');
    const s = (totalSeconds % 60).toString().padStart(2, '0');
    return `${m}:${s}`;
  };

  return (
    <View style={styles.card}>
      <View style={styles.header}>
        <Text style={styles.title}>RENDER MONITOR</Text>
        <View style={styles.statusBadge}>
          <View style={[styles.dot, { backgroundColor: getStatusColor() }]} />
          <Text style={[styles.statusText, { color: getStatusColor() }]}>{status}</Text>
        </View>
      </View>

      <View style={styles.grid}>
        <MetricBox label="FPS" value={progress?.fps !== undefined && progress?.fps !== null ? progress.fps.toFixed(1) : 'N/A'} />
        <MetricBox label="SPEED" value={progress?.speed !== undefined && progress?.speed !== null ? `${progress.speed.toFixed(2)}x` : 'N/A'} />
        <MetricBox label="PROGRESS" value={progressPercent} />
        <MetricBox label="FRAME" value={progress?.frame !== undefined && progress?.frame !== null ? progress.frame.toString() : 'N/A'} />
        <MetricBox label="TIME" value={formatTime(progress?.time)} />
        <MetricBox label="BITRATE" value={progress?.bitrate !== undefined && progress?.bitrate !== null ? `${(progress.bitrate / 1000).toFixed(0)} kb/s` : 'N/A'} />
      </View>

      <View style={styles.footer}>
        <View>
          <Text style={styles.footerText}>Requested: <Text style={styles.highlight}>{requestedEncoder || 'N/A'}</Text></Text>
          <Text style={styles.footerText}>Actual: <Text style={styles.highlight}>{actualEncoder || 'N/A'} ({hardwareEncoderUsed ? 'HW' : 'SW'})</Text></Text>
        </View>
        <View style={{ alignItems: 'flex-end' }}>
          <Text style={styles.footerText}>Processing: <Text style={styles.highlight}>FFmpeg CPU</Text></Text>
        </View>
      </View>
    </View>
  );
}

const MetricBox = ({ label, value }: { label: string, value: string }) => (
  <View style={styles.metricBox}>
    <Text style={styles.metricLabel}>{label}</Text>
    <Text style={styles.metricValue}>{value}</Text>
  </View>
);

const styles = StyleSheet.create({
  card: { backgroundColor: Colors.surface, borderRadius: 12, padding: Spacing.md, borderWidth: 1, borderColor: Colors.border, marginBottom: Spacing.md },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: Spacing.md },
  title: { color: Colors.text, fontSize: 14, fontWeight: 'bold', letterSpacing: 1 },
  statusBadge: { flexDirection: 'row', alignItems: 'center', backgroundColor: Colors.surfaceElevated, paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12 },
  dot: { width: 8, height: 8, borderRadius: 4, marginRight: 6 },
  statusText: { fontSize: 12, fontWeight: 'bold' },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.sm },
  metricBox: { width: '31%', backgroundColor: Colors.surfaceElevated, padding: Spacing.sm, borderRadius: 8, alignItems: 'center' },
  metricLabel: { color: Colors.textSecondary, fontSize: 10, marginBottom: 4 },
  metricValue: { color: Colors.text, fontSize: 14, fontWeight: 'bold' },
  footer: { marginTop: Spacing.md, paddingTop: Spacing.sm, borderTopWidth: 1, borderTopColor: Colors.border, flexDirection: 'row', justifyContent: 'space-between' },
  footerText: { color: Colors.textSecondary, fontSize: 11 },
  highlight: { color: Colors.primary, fontWeight: 'bold' }
});