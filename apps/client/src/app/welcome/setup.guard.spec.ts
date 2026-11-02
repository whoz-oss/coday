import { TestBed } from '@angular/core/testing'
import { provideRouter, Router, UrlTree } from '@angular/router'
import { resetSetupGuardCache, setupGuard, welcomeGuard, wizardRequired } from './setup.guard'
import { SetupStateService } from './setup-state.service'
import { SetupStatus } from './setup.models'

const status = (patch: Partial<SetupStatus> = {}): SetupStatus =>
  ({
    mode: 'laptop',
    setupEnabled: true,
    setupDone: false,
    steps: {},
    apply: { status: 'idle' },
    ...patch,
  }) as SetupStatus

describe('wizardRequired', () => {
  it('is required on a laptop first run', () => {
    expect(wizardRequired(status())).toBe(true)
  })

  it.each([
    ['setup done', { setupDone: true }],
    ['managed mode (existing install, external AgentOS, --auth)', { mode: 'managed' as const, setupEnabled: false }],
    ['setup disabled', { setupEnabled: false }],
  ])('is not required for %s', (_label, patch) => {
    expect(wizardRequired(status(patch))).toBe(false)
  })

  it('is not required when the status is unknown (never trap the user)', () => {
    expect(wizardRequired(null)).toBe(false)
  })
})

describe('setup guards', () => {
  let refreshStatus: jest.Mock

  const run = async (guard: typeof setupGuard): Promise<boolean | UrlTree> =>
    TestBed.runInInjectionContext(() => guard({} as never, {} as never)) as Promise<boolean | UrlTree>

  beforeEach(() => {
    resetSetupGuardCache()
    refreshStatus = jest.fn()
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: SetupStateService, useValue: { refreshStatus } }],
    })
  })

  it('redirects to /welcome while the setup is not done', async () => {
    refreshStatus.mockResolvedValue(status())
    const result = await run(setupGuard)
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/welcome')
  })

  it('lets everything through for non-laptop modes and unreadable status', async () => {
    refreshStatus.mockResolvedValue(status({ mode: 'managed', setupEnabled: false }))
    expect(await run(setupGuard)).toBe(true)
    refreshStatus.mockResolvedValue(null)
    expect(await run(setupGuard)).toBe(true)
  })

  it('stops asking the server once the setup is known to be done', async () => {
    refreshStatus.mockResolvedValue(status({ setupDone: true }))
    expect(await run(setupGuard)).toBe(true)
    expect(await run(setupGuard)).toBe(true)
    expect(refreshStatus).toHaveBeenCalledTimes(1)
  })

  it('keeps /welcome reachable in every mode: wizard, loading page after setup, loading page without a wizard', async () => {
    refreshStatus.mockResolvedValue(status())
    expect(await run(welcomeGuard)).toBe(true)
    refreshStatus.mockResolvedValue(status({ setupDone: true }))
    expect(await run(welcomeGuard)).toBe(true)
    refreshStatus.mockResolvedValue(status({ mode: 'managed', setupEnabled: false }))
    expect(await run(welcomeGuard)).toBe(true)
  })

  it('keeps /welcome reachable when the status cannot be read', async () => {
    refreshStatus.mockResolvedValue(null)
    expect(await run(welcomeGuard)).toBe(true)
  })
})
