import AsyncStorage from '@react-native-async-storage/async-storage';
import { CommandEntry } from '../types/ffmpeg';

const LIBRARY_KEY = '@hve_command_library';

export const CommandLibraryService = {
  parseAndSaveFile: async (fileContent: string): Promise<number> => {
    const existing = await CommandLibraryService.getCommands();
    const existingHashes = new Set(existing.map(c => c.command.trim()));
    
    const newCommands: CommandEntry[] = [];
    const blocks = fileContent.split('################################');
    
    let currentName = '';
    
    for (const block of blocks) {
      const trimmed = block.trim();
      if (!trimmed) continue;

      if (trimmed.startsWith('FILE NO:')) {
        const nameMatch = trimmed.match(/FILE NAME:\s*(.+)/i);
        if (nameMatch) {
          currentName = nameMatch[1].replace('Project File/', '').replace('.bat', '').trim();
        }
      } else if (trimmed.startsWith('for ') || trimmed.startsWith('ffmpeg')) {
        if (currentName && trimmed && !existingHashes.has(trimmed)) {
          newCommands.push({
            id: Math.random().toString(36).substring(7),
            name: currentName,
            command: trimmed,
            category: currentName.includes('Zoom') ? 'Zoom' : currentName.includes('Flip') ? 'Flip' : 'Transform'
          });
          existingHashes.add(trimmed);
          currentName = '';
        }
      }
    }

    if (newCommands.length > 0) {
      await AsyncStorage.setItem(LIBRARY_KEY, JSON.stringify([...existing, ...newCommands]));
    }
    return newCommands.length;
  },

  getCommands: async (): Promise<CommandEntry[]> => {
    const data = await AsyncStorage.getItem(LIBRARY_KEY);
    return data ? JSON.parse(data) : [];
  }
};