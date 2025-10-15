export function debugLog(context: string, ...args: any[]) {
  console.log(`${new Date().toISOString()} ${context}`, ...args)
}
