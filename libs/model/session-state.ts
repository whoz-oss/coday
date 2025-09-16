/**
 * @fileoverview Shared interface for session state between frontend and backend
 */

/**
 * Interface representing the current session state including projects and threads information
 */
export interface SessionState {
  projects: {
    list: Array<{ name: string }> | null;  // null if project locked by options
    current: string | null;                 // currently selected project name
    canCreate: boolean;                     // false if project locked by options
  };
  threads: {
    list: Array<{ id: string; name: string; modifiedDate: string }> | null;  // null if no project selected
    current: string | null;  // currently selected thread id
  };
}