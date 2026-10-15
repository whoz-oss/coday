const { execSync } = require('child_process')
const fs = require('fs')
const os = require('os')

/**
 * Writes "KEY=VALUE" to $GITHUB_ENV so subsequent steps see it.
 * Mirrors what @actions/core exportVariable() does internally.
 */
function exportVariable(name, value) {
  const delimiter = `ghadelimiter_${Math.random().toString(36).slice(2)}`
  const envFile = process.env.GITHUB_ENV
  if (!envFile) {
    // Fallback for local testing outside GHA
    process.env[name] = value
    return
  }
  // Multiline-safe format required by GHA
  fs.appendFileSync(envFile, `${name}<<${delimiter}${os.EOL}${value}${os.EOL}${delimiter}${os.EOL}`)
}

// Export runner cache credentials — available here because Node.js actions
// are launched directly by the runner agent, which injects these variables.
exportVariable('ACTIONS_RESULTS_URL', process.env.ACTIONS_RESULTS_URL || '')
exportVariable('ACTIONS_RUNTIME_TOKEN', process.env.ACTIONS_RUNTIME_TOKEN || '')

// Compute and export the merge-base SHA for Nx cache key isolation
// GitHub converts input names to uppercase and replaces hyphens with underscores
const baseRef = process.env['INPUT_BASE-REF'] || process.env.INPUT_BASE_REF || process.env.GITHUB_BASE_REF
let baseSha = 'unknown'
if (baseRef) {
  try {
    baseSha = execSync(`git merge-base HEAD origin/${baseRef}`).toString().trim()
  } catch (e) {
    console.warn(`[expose-runtime] Could not compute merge-base for ${baseRef}: ${e.message}`)
    baseSha = execSync('git rev-parse HEAD').toString().trim()
  }
} else {
  console.warn('[expose-runtime] No base ref available, using HEAD SHA for cache key')
  baseSha = execSync('git rev-parse HEAD').toString().trim()
}
exportVariable('NX_CACHE_BASE_SHA', baseSha)

console.log(`[expose-runtime] ACTIONS_RESULTS_URL set: ${!!process.env.ACTIONS_RESULTS_URL}`)
console.log(`[expose-runtime] NX_CACHE_BASE_SHA: ${baseSha}`)
