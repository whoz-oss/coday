import { releaseChangelog, releasePublish, releaseVersion } from 'nx/release'

export interface NotifyCodayEnv {
  baseUrl: string | undefined
  promptId: string | undefined
  runUrl: string
  branch: string
  commitSha: string
}

export function buildNotifyEnv(): NotifyCodayEnv {
  return {
    baseUrl: process.env['CODAY_WEBHOOK_URL'],
    promptId: process.env['CODAY_CI_HEALING_PROMPT_ID'],
    runUrl: process.env['GITHUB_SERVER_URL']
      ? `${process.env['GITHUB_SERVER_URL']}/${process.env['GITHUB_REPOSITORY']}/actions/runs/${process.env['GITHUB_RUN_ID']}`
      : 'local',
    branch: process.env['GITHUB_REF_NAME'] ?? 'unknown',
    commitSha: process.env['GITHUB_SHA'] ?? 'unknown',
  }
}

export async function notifyCoday(
  phase: string,
  error: unknown,
  context: Record<string, unknown>,
  env: NotifyCodayEnv = buildNotifyEnv()
): Promise<void> {
  if (!env.baseUrl || !env.promptId) {
    console.warn('[self-healing] Skipped: CODAY_WEBHOOK_URL or CODAY_CI_HEALING_PROMPT_ID not set')
    return
  }
  const webhookUrl = `${env.baseUrl}/api/webhooks/${env.promptId}/execute`
  console.log(`[self-healing] Notifying Coday of failure at phase: ${phase}`)
  try {
    await fetch(webhookUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-forwarded-email': 'ci-bot@coday',
      },
      body: JSON.stringify({
        title: `Release failure: ${phase}`,
        awaitFinalAnswer: false,
        phase,
        error: String(error),
        runUrl: env.runUrl,
        branch: env.branch,
        commitSha: env.commitSha,
        ...context,
      }),
    })
    console.log('[self-healing] Coday notified successfully')
  } catch (fetchError) {
    console.error('[self-healing] Failed to notify Coday:', fetchError)
  }
}

export async function main(): Promise<void> {
  // Phase 1: Version bumping
  console.log('\n--- Phase 1: releaseVersion ---')
  let projectsVersionData: Awaited<ReturnType<typeof releaseVersion>>['projectsVersionData']
  let releaseGraph: Awaited<ReturnType<typeof releaseVersion>>['releaseGraph']

  const env = buildNotifyEnv()

  try {
    const result = await releaseVersion({ verbose: true })
    projectsVersionData = result.projectsVersionData
    releaseGraph = result.releaseGraph
  } catch (e) {
    await notifyCoday('releaseVersion', e, { projects: ['server', 'client', 'web', 'desktop', 'desktop-twin'] }, env)
    process.exit(1)
  }

  // Phase 2: Changelog + GitHub release
  console.log('\n--- Phase 2: releaseChangelog ---')
  try {
    await releaseChangelog({ versionData: projectsVersionData, releaseGraph, verbose: true })
  } catch (e) {
    await notifyCoday('releaseChangelog', e, { projectsVersionData }, env)
    process.exit(1)
  }

  // Phase 3: npm publish
  console.log('\n--- Phase 3: releasePublish ---')
  let publishResults: Awaited<ReturnType<typeof releasePublish>>
  try {
    publishResults = await releasePublish({ releaseGraph, versionData: projectsVersionData, verbose: true })
  } catch (e) {
    await notifyCoday('releasePublish', e, { projectsVersionData }, env)
    process.exit(1)
  }

  const failures = Object.entries(publishResults).filter(([, result]) => result.code !== 0)
  if (failures.length > 0) {
    await notifyCoday(
      'releasePublish',
      `${failures.length} project(s) failed to publish`,
      {
        failedProjects: failures.map(([name]) => name),
        allResults: Object.fromEntries(Object.entries(publishResults).map(([name, r]) => [name, r.code])),
      },
      env
    )
    process.exit(1)
  }

  console.log('\nRelease completed successfully.')
  process.exit(0)
}

// Only run when executed directly, not when imported (e.g. by Jest)
if (process.env['NODE_ENV'] !== 'test') {
  void main()
}
