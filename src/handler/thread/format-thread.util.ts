export function formatThread(
  threadId: string,
  name: string,
  currentThreadId: string | null
): string {
  return `${threadId} : ${currentThreadId === threadId ? "[CURRENT] " : ""}${name}`
}
