import fs from "node:fs";
import path from "node:path";

/**
 * Session Index - Persistent mapping for chat_id → session_id
 *
 * This index enables OpenClaw to reuse existing sessions for the same chat_id
 * instead of creating new sessions on every message.
 *
 * @see https://docs.openclaw.ai/concepts/session-persistence
 */

export type SessionIndexMapping = {
  version: number;
  created_at: string;
  last_rebuilt?: string;
  last_updated?: string;
  last_consolidation?: string;
  mappings: Record<string, string>; // chat_id → session_id
  total_sessions: number;
};

const DEFAULT_INDEX_PATH = "./session_index.json";

/**
 * Load session index from file
 */
export function loadSessionIndex(indexPath: string = DEFAULT_INDEX_PATH): SessionIndexMapping {
  const resolvedPath = path.resolve(indexPath);
  
  try {
    const raw = fs.readFileSync(resolvedPath, "utf-8");
    const parsed = JSON.parse(raw) as SessionIndexMapping;
    return parsed;
  } catch (error) {
    // Return empty index if file doesn't exist or is invalid
    return {
      version: 1,
      created_at: new Date().toISOString(),
      mappings: {},
      total_sessions: 0,
    };
  }
}

/**
 * Save session index to file
 */
export function saveSessionIndex(
  indexPath: string,
  index: SessionIndexMapping,
): void {
  const resolvedPath = path.resolve(indexPath);
  
  index.last_updated = new Date().toISOString();
  index.total_sessions = Object.keys(index.mappings).length;
  
  fs.mkdirSync(path.dirname(resolvedPath), { recursive: true });
  fs.writeFileSync(resolvedPath, JSON.stringify(index, null, 2), "utf-8");
}

/**
 * Get or create session index
 */
export function getOrCreateSessionIndex(
  indexPath: string = DEFAULT_INDEX_PATH,
): SessionIndexMapping {
  return loadSessionIndex(indexPath);
}

/**
 * Lookup session_id by chat_id
 * @returns session_id or null if not found
 */
export function lookupSessionByChatId(
  chatId: string,
  indexPath: string = DEFAULT_INDEX_PATH,
): string | null {
  const index = loadSessionIndex(indexPath);
  return index.mappings[chatId] ?? null;
}

/**
 * Register a new session mapping
 */
export function registerSessionMapping(
  chatId: string,
  sessionId: string,
  indexPath: string = DEFAULT_INDEX_PATH,
): void {
  const index = loadSessionIndex(indexPath);
  index.mappings[chatId] = sessionId;
  saveSessionIndex(indexPath, index);
}

/**
 * Remove a session mapping
 */
export function removeSessionMapping(
  chatId: string,
  indexPath: string = DEFAULT_INDEX_PATH,
): void {
  const index = loadSessionIndex(indexPath);
  delete index.mappings[chatId];
  saveSessionIndex(indexPath, index);
}

/**
 * Scan session transcripts for a chat_id
 * @returns session_id or null if not found
 */
export function scanSessionsForChatId(
  chatId: string,
  sessionsDir: string,
): string | null {
  const resolvedDir = path.resolve(sessionsDir);
  
  if (!fs.existsSync(resolvedDir)) {
    return null;
  }
  
  const files = fs.readdirSync(resolvedDir).filter(f => f.endsWith('.jsonl'));
  
  for (const file of files) {
    const filePath = path.join(resolvedDir, file);
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      if (content.includes(chatId)) {
        return file.replace('.jsonl', '');
      }
    } catch {
      // Skip unreadable files
      continue;
    }
  }
  
  return null;
}

/**
 * Scan all sessions and build chat_id → session_id mappings
 */
export function scanAllSessions(
  sessionsDir: string,
): Record<string, string> {
  const resolvedDir = path.resolve(sessionsDir);
  const mappings: Record<string, string> = {};
  
  if (!fs.existsSync(resolvedDir)) {
    return mappings;
  }
  
  const files = fs.readdirSync(resolvedDir).filter(f => f.endsWith('.jsonl'));
  
  for (const file of files) {
    const filePath = path.join(resolvedDir, file);
    const sessionId = file.replace('.jsonl', '');
    
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      
      // Look for common chat_id patterns
      const patterns = [
        /"telegram:\d+"/g,
        /"discord:\d+"/g,
        /"whatsapp:\d+"/g,
        /"signal:\d+"/g,
      ];
      
      for (const pattern of patterns) {
        const matches = content.match(pattern);
        if (matches) {
          for (const match of matches) {
            const chatId = match.replace(/"/g, '');
            // Only keep the most recent session per chat_id
            if (!mappings[chatId]) {
              mappings[chatId] = sessionId;
            }
          }
        }
      }
    } catch {
      // Skip unreadable files
      continue;
    }
  }
  
  return mappings;
}

/**
 * Rebuild session index from scratch
 */
export function rebuildIndex(
  sessionsDir: string,
  indexPath: string = DEFAULT_INDEX_PATH,
): SessionIndexMapping {
  const mappings = scanAllSessions(sessionsDir);
  
  const index: SessionIndexMapping = {
    version: 1,
    created_at: new Date().toISOString(),
    last_rebuilt: new Date().toISOString(),
    mappings,
    total_sessions: Object.keys(mappings).length,
  };
  
  saveSessionIndex(indexPath, index);
  return index;
}

/**
 * Find all sessions for a chat_id
 */
export function findSessionsForChatId(
  chatId: string,
  sessionsDir: string,
): string[] {
  const resolvedDir = path.resolve(sessionsDir);
  const sessions: string[] = [];
  
  if (!fs.existsSync(resolvedDir)) {
    return sessions;
  }
  
  const files = fs.readdirSync(resolvedDir).filter(f => f.endsWith('.jsonl'));
  
  for (const file of files) {
    const filePath = path.join(resolvedDir, file);
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      if (content.includes(chatId)) {
        sessions.push(file.replace('.jsonl', ''));
      }
    } catch {
      continue;
    }
  }
  
  return sessions;
}
