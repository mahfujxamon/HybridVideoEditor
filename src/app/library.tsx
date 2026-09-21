import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet, FlatList, Pressable, Alert } from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import * as FileSystem from 'expo-file-system';
import { useRouter } from 'expo-router';

import { CommandLibraryService } from '../services/commandLibrary';
import { CommandEntry } from '../types/ffmpeg';
import { Colors, Spacing } from '../constants/theme';

export default function LibraryScreen() {
  const [commands, setCommands] = useState<CommandEntry[]>([]);
  const router = useRouter();

  const loadLibrary = async () => {
    const data = await CommandLibraryService.getCommands();
    setCommands(data);
  };

  useEffect(() => {
    loadLibrary();
  }, []);

  const importFile = async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({ type: 'text/plain' });
      if (!result.canceled && result.assets[0]) {
        const content = await FileSystem.readAsStringAsync(result.assets[0].uri);
        const count = await CommandLibraryService.parseAndSaveFile(content);
        Alert.alert("Import Success", `Successfully imported ${count} new commands.`);
        loadLibrary();
      }
    } catch (e) {
      Alert.alert("Import Error", "Failed to read or parse the file.");
    }
  };

  const useCommand = (command: string) => {
    // Auto-detect if it's a batch command
    const type = command.trim().toLowerCase().startsWith('for ') ? 'WINDOWS' : 'DIRECT';
    router.push({ pathname: '/', params: { command, type } });
  };

  const renderItem = ({ item }: { item: CommandEntry }) => (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <Text style={styles.cardTitle}>{item.name}</Text>
        <Text style={styles.badge}>{item.category}</Text>
      </View>
      <Text style={styles.commandText} numberOfLines={3}>{item.command}</Text>
      <Pressable style={styles.useBtn} onPress={() => useCommand(item.command)}>
        <Text style={styles.useBtnText}>USE COMMAND</Text>
      </Pressable>
    </View>
  );

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Command Library</Text>
        <Pressable style={styles.importBtn} onPress={importFile}>
          <Text style={styles.importBtnText}>IMPORT TXT</Text>
        </Pressable>
      </View>

      <FlatList
        data={commands}
        keyExtractor={item => item.id}
        renderItem={renderItem}
        contentContainerStyle={styles.list}
        ListEmptyComponent={<Text style={styles.emptyText}>No commands imported yet. Import "all codes.txt".</Text>}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: Colors.background, paddingTop: 60 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: Spacing.lg, marginBottom: Spacing.md },
  title: { color: Colors.text, fontSize: 24, fontWeight: 'bold' },
  importBtn: { backgroundColor: Colors.accent, paddingHorizontal: Spacing.md, paddingVertical: Spacing.sm, borderRadius: 8 },
  importBtnText: { color: Colors.text, fontWeight: 'bold', fontSize: 12 },
  list: { padding: Spacing.lg, paddingBottom: 100 },
  card: { backgroundColor: Colors.surface, padding: Spacing.md, borderRadius: 12, marginBottom: Spacing.md, borderWidth: 1, borderColor: Colors.border },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: Spacing.sm },
  cardTitle: { color: Colors.text, fontWeight: 'bold', fontSize: 16, flex: 1 },
  badge: { color: Colors.primary, fontSize: 10, fontWeight: 'bold', backgroundColor: Colors.surfaceElevated, paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4, overflow: 'hidden' },
  commandText: { color: Colors.textSecondary, fontFamily: 'monospace', fontSize: 10, marginBottom: Spacing.md },
  useBtn: { backgroundColor: Colors.surfaceElevated, padding: Spacing.sm, alignItems: 'center', borderRadius: 8 },
  useBtnText: { color: Colors.primary, fontWeight: 'bold', fontSize: 12 },
  emptyText: { color: Colors.textSecondary, textAlign: 'center', marginTop: 40 }
});