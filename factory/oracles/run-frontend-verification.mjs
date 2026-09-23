import { spawnSync } from 'node:child_process'
import { resolveFrontOraclePlan } from '../lib/forge-front-oracle-resolution.mjs'

const files = JSON.parse(process.env.FACTORY_FRONT_MODIFIED_FILES ?? '[]')
const plan = resolveFrontOraclePlan({
  repoRoot: process.cwd(),
  files,
  hostMapRaw: process.env.FACTORY_FRONT_BUILD_HOST_MAP,
  buildTemplate: 'unused',
})
const commands = [
  [
    'pnpm',
    [
      'nx',
      'run-many',
      '--target=build',
      '--configuration=development',
      `--projects=${plan.build.buildHosts.join(',')}`,
      '--skip-nx-cache',
    ],
  ],
  plan.tests.ownersWithTestTarget.length
    ? [
        'pnpm',
        [
          'nx',
          'run-many',
          `--target=${plan.tests.target}`,
          `--projects=${plan.tests.ownersWithTestTarget.join(',')}`,
          '--skip-nx-cache',
        ],
      ]
    : null,
].filter(Boolean)
if (commands.length !== 2) process.exit(2)
for (const [executable, args] of commands) {
  const result = spawnSync(executable, args, { cwd: process.cwd(), stdio: 'inherit', shell: false })
  if (result.error || result.status !== 0) process.exit(result.status ?? 2)
}
process.exit(0)
