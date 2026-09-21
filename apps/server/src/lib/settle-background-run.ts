/**
 * Preserve the lifecycle of a background run while keeping its operational
 * errors non-fatal to the request that initiated it.
 */
export function settleBackgroundRun(
  run: () => Promise<unknown>,
  reportError: (error: unknown) => void
): Promise<undefined> {
  return run().then(
    () => undefined,
    (error) => {
      reportError(error)
      return undefined
    }
  )
}
