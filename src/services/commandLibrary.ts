import * as FileSystem from 'expo-file-system';
import { CommandEntry } from '../types/ffmpeg';

const LIBRARY_FILE = FileSystem.documentDirectory + 'hve_command_library.json';

const generateHash = (str: string) => {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = Math.imul(31, hash) + str.charCodeAt(i) | 0;
  }
  return hash.toString();
};

export const CommandLibraryService = {
  parseAndSaveFile: async (fileContent: string): Promise<number> => {
    const existing = await CommandLibraryService.getCommands();
    const existingHashes = new Set(existing.map(c => generateHash(c.command.trim())));
    
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
        const cmdHash = generateHash(trimmed);
        if (currentName && trimmed && !existingHashes.has(cmdHash)) {
          newCommands.push({
            id: Math.random().toString(36).substring(7),
            name: currentName,
            command: trimmed,
            category: currentName.includes('Zoom') ? 'Zoom' : currentName.includes('Flip') ? 'Flip' : 'Transform'
          });
          existingHashes.add(cmdHash);
          currentName = '';
        }
      }
    }

    if (newCommands.length > 0) {
      await FileSystem.writeAsStringAsync(LIBRARY_FILE, JSON.stringify([...existing, ...newCommands]));
    }
    return newCommands.length;
  },

  getCommands: async (): Promise<CommandEntry[]> => {
    try {
      const info = await FileSystem.getInfoAsync(LIBRARY_FILE);
      if (info.exists) {
        const data = await FileSystem.readAsStringAsync(LIBRARY_FILE);
        return JSON.parse(data);
      }
    } catch (e) {
      console.error('Failed to read command library', e);
    }
    return [];
  }
};