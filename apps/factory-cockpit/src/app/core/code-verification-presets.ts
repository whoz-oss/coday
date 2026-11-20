/**
 * Authoring-only, closed set of Factory code verifiers.
 *
 * This is intentionally not runtime discovery or an authorization mechanism:
 * Factory remains the authority for whether a verifier can run for a definition.
 */
export interface CodeVerificationPreset {
  id: 'build' | 'frontend-build' | 'frontend-tests'
  label: string
  hint: string
}

export const CODE_VERIFICATION_PRESETS: readonly CodeVerificationPreset[] = [
  {
    id: 'build',
    label: 'Build',
    hint: 'Repository-defined verification. The target repository must declare build in factory/verification.json (Coday: build, lint, tests and OpenAPI).',
  },
  {
    id: 'frontend-build',
    label: 'Frontend build',
    hint: 'Factory Nx development build for server-configured buildHosts (default: aphrodite, admin, agentic-studio, copilot-chat). It needs specific server/project configuration and is not ready by default for Coday; use Build.',
  },
  {
    id: 'frontend-tests',
    label: 'Frontend tests',
    hint: 'Factory-owned Nx frontend-test verifier. It needs PASS-agent ancestor claims.modifiedFiles plus specific server/project configuration and is not ready by default for Coday; use Build.',
  },
] as const

export function isCodeVerificationPreset(value: string | undefined): value is CodeVerificationPreset['id'] {
  return CODE_VERIFICATION_PRESETS.some((preset) => preset.id === value)
}

export function codeVerificationPreset(value: string | undefined): CodeVerificationPreset | undefined {
  return CODE_VERIFICATION_PRESETS.find((preset) => preset.id === value)
}
