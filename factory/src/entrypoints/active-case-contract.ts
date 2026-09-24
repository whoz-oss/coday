import * as activeCase from '../lib/active-case.js'

const expectedExports = [
  'clearActiveCaseId',
  'getActiveCaseId',
  'getActiveCaseIds',
  'registerActiveCase',
  'setActiveCaseId',
  'unregisterActiveCase',
]

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(`active-case contract violation: ${message}`)
}

export function verifyActiveCaseContract(): void {
  assert(
    JSON.stringify(Object.keys(activeCase).sort()) === JSON.stringify(expectedExports),
    'unexpected public exports'
  )
  assert(activeCase.getActiveCaseId() === null, 'registry must initially be empty')
  assert(activeCase.getActiveCaseIds().length === 0, 'registry snapshot must initially be empty')

  activeCase.registerActiveCase('contract-a', 'editor')
  activeCase.registerActiveCase('contract-a', 'ignored')
  activeCase.registerActiveCase('contract-b', 'reviewer')
  assert(activeCase.getActiveCaseId() === 'contract-a', 'first registered case must remain active')
  assert(
    JSON.stringify(activeCase.getActiveCaseIds()) === JSON.stringify(['contract-a', 'contract-b']),
    'registration must be ordered and idempotent'
  )

  const snapshot = activeCase.getActiveCaseIds()
  activeCase.unregisterActiveCase('contract-a')
  activeCase.unregisterActiveCase('contract-a')
  assert(snapshot.length === 2, 'snapshots must not expose mutable registry state')

  activeCase.setActiveCaseId('contract-legacy')
  activeCase.clearActiveCaseId(null)
  activeCase.clearActiveCaseId('contract-b')
  assert(activeCase.getActiveCaseId() === 'contract-legacy', 'legacy API must delegate to the registry')
  activeCase.clearActiveCaseId('contract-legacy')
  assert(activeCase.getActiveCaseId() === null, 'contract exercise must leave the registry empty')
}

try {
  verifyActiveCaseContract()
} catch (error) {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
}

export {
  clearActiveCaseId,
  getActiveCaseId,
  getActiveCaseIds,
  registerActiveCase,
  setActiveCaseId,
  unregisterActiveCase,
} from '../lib/active-case.js'
