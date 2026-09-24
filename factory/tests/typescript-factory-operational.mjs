// Targeted tests for the generated operational bundle. Run only after generation.
import {
  createShutdownController,
  registerActiveCase,
  unregisterActiveCase,
  getActiveCaseIds,
} from '../runtime/factory-operational.mjs'

const calls = []
registerActiveCase('case-a')
const controller = createShutdownController({
  activeCaseIds: getActiveCaseIds,
  caseTerminator: { terminate: async (id) => calls.push(['terminate', id]) },
  currentRun: () => ({ filePath: '/tmp/run', _startedAt: 0 }),
  endRun: (_run, status, facts) => calls.push(['endRun', status, facts]),
  rejectPendingGates: () => calls.push(['reject']),
  warn: () => {},
  exit: (code) => calls.push(['exit', code]),
})
await controller.handle('SIGTERM')
await controller.handle('SIGTERM')
unregisterActiveCase('case-a')
if (calls.filter(([name]) => name === 'terminate').length !== 1) throw new Error('shutdown is not idempotent')
if (calls.filter(([name]) => name === 'endRun').length !== 1) throw new Error('run ended more than once')
if (calls.at(-1)?.[1] !== 1) throw new Error('shutdown did not request exit code 1')
