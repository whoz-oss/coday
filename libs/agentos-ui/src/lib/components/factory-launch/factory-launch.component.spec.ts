import { signal } from '@angular/core'
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing'
import { By } from '@angular/platform-browser'
import {
  AgentConfig,
  AgentConfigControllerService,
  IntegrationConfigControllerService,
  Namespace,
} from '@whoz-oss/agentos-api-client'
import { FactoryLaunchRequest } from '../../services/factory-api.service'
import { FactoryLaunchStateService, LaunchResult, LaunchStatus } from '../../services/factory-launch-state.service'
import { FactoryLaunchComponent } from './factory-launch.component'

describe('FactoryLaunchComponent', () => {
  let fixture: ComponentFixture<FactoryLaunchComponent>

  // Real Angular signals so computed()s in the component track them.
  const agentsSignal = signal<AgentConfig[]>([])
  const agentsLoadingSignal = signal(false)
  const agentsErrorSignal = signal<string | null>(null)
  const launchStatusSignal = signal<LaunchStatus>('idle')
  const launchErrorSignal = signal<string | null>(null)
  const launchResultSignal = signal<LaunchResult | null>(null)
  const derivedRootSignal = signal<string | null>(null)
  const rootLoadingSignal = signal(false)
  const rootErrorSignal = signal<string | null>(null)

  let launchMock: jest.Mock
  let resetMock: jest.Mock
  let loadAgentsMock: jest.Mock
  let deriveRootMock: jest.Mock
  let deriveTwoAgentRootsMock: jest.Mock
  let clearDerivedRootMock: jest.Mock

  const stateServiceMock = {
    get agents() {
      return agentsSignal
    },
    get agentsLoading() {
      return agentsLoadingSignal
    },
    get agentsError() {
      return agentsErrorSignal
    },
    get launchStatus() {
      return launchStatusSignal
    },
    get launchError() {
      return launchErrorSignal
    },
    get launchResult() {
      return launchResultSignal
    },
    get derivedRoot() {
      return derivedRootSignal
    },
    get rootLoading() {
      return rootLoadingSignal
    },
    get rootError() {
      return rootErrorSignal
    },
    get loadAgents() {
      return loadAgentsMock
    },
    get reset() {
      return resetMock
    },
    get launch() {
      return launchMock
    },
    get deriveRootForAgent() {
      return deriveRootMock
    },
    get deriveTwoAgentRoots() {
      return deriveTwoAgentRootsMock
    },
    get clearDerivedRoot() {
      return clearDerivedRootMock
    },
    usableAgents: jest.fn(() => []),
  }

  const agentConfigControllerMock = { searchAgentConfig: jest.fn() }
  const integrationConfigControllerMock = { listIntegrationConfig: jest.fn() }

  beforeEach(async () => {
    launchMock = jest.fn()
    resetMock = jest.fn()
    loadAgentsMock = jest.fn()
    deriveRootMock = jest.fn()
    deriveTwoAgentRootsMock = jest.fn()
    clearDerivedRootMock = jest.fn()

    agentsSignal.set([])
    agentsLoadingSignal.set(false)
    agentsErrorSignal.set(null)
    launchStatusSignal.set('idle')
    launchErrorSignal.set(null)
    launchResultSignal.set(null)
    derivedRootSignal.set(null)
    rootLoadingSignal.set(false)
    rootErrorSignal.set(null)

    await TestBed.configureTestingModule({
      imports: [FactoryLaunchComponent],
      providers: [
        { provide: FactoryLaunchStateService, useValue: stateServiceMock },
        { provide: AgentConfigControllerService, useValue: agentConfigControllerMock },
        { provide: IntegrationConfigControllerService, useValue: integrationConfigControllerMock },
      ],
    }).compileComponents()

    fixture = TestBed.createComponent(FactoryLaunchComponent)
    fixture.componentRef.setInput('namespaceId', 'ns-test')
    fixture.detectChanges()
  })

  afterEach(() => TestBed.resetTestingModule())

  it('creates the component', () => {
    expect(fixture.componentInstance).toBeTruthy()
  })

  // ---------------------------------------------------------------------------
  // Initial state
  // ---------------------------------------------------------------------------

  describe('initial state (fix-loop)', () => {
    it('shows the agent field', () => {
      expect(fixture.debugElement.query(By.css('label[for="fl-agent"]'))).toBeTruthy()
    })

    it('does not show analyst or editor fields', () => {
      expect(fixture.debugElement.query(By.css('label[for="fl-analyst"]'))).toBeNull()
      expect(fixture.debugElement.query(By.css('label[for="fl-editor"]'))).toBeNull()
    })

    it('submit button is disabled when agent is empty', () => {
      const submitBtn = fixture.debugElement.query(By.css('button[type="submit"]'))
      expect(submitBtn.nativeElement.disabled).toBe(true)
    })
  })

  // ---------------------------------------------------------------------------
  // Workflow switching
  // ---------------------------------------------------------------------------

  describe('workflow change to us-loop', () => {
    function switchToUsLoop() {
      const select = fixture.debugElement.query(By.css('#fl-workflow')).nativeElement as HTMLSelectElement
      select.value = 'us-loop'
      select.dispatchEvent(new Event('change'))
      fixture.detectChanges()
    }

    it('hides the agent field after switching to us-loop', () => {
      switchToUsLoop()
      expect(fixture.debugElement.query(By.css('label[for="fl-agent"]'))).toBeNull()
    })

    it('shows analyst and editor fields after switching to us-loop', () => {
      switchToUsLoop()
      expect(fixture.debugElement.query(By.css('label[for="fl-analyst"]'))).toBeTruthy()
      expect(fixture.debugElement.query(By.css('label[for="fl-editor"]'))).toBeTruthy()
    })

    it('submit is DISABLED for us-loop when analyst or editor is empty', () => {
      switchToUsLoop()
      const taskEl = fixture.debugElement.query(By.css('#fl-task')).nativeElement as HTMLTextAreaElement
      taskEl.value = 'Some task'
      taskEl.dispatchEvent(new Event('input'))
      fixture.detectChanges()
      // Neither analyst nor editor selected yet
      const submitBtn = fixture.debugElement.query(By.css('button[type="submit"]'))
      expect(submitBtn.nativeElement.disabled).toBe(true)
    })

    it('submit is DISABLED for us-loop when only analyst is selected (editor missing)', fakeAsync(() => {
      switchToUsLoop()
      derivedRootSignal.set('/repo/sprint') // simulate resolved root
      fixture.componentInstance['form'].controls.task.setValue('Some task')
      fixture.componentInstance['form'].controls.analyst.setValue('factory-analyst')
      // editor left empty
      fixture.detectChanges()
      tick()
      const submitBtn = fixture.debugElement.query(By.css('button[type="submit"]'))
      expect(submitBtn.nativeElement.disabled).toBe(true)
    }))

    it('submit is enabled for us-loop when both analyst and editor are selected and no rootError', fakeAsync(() => {
      switchToUsLoop()
      derivedRootSignal.set('/repo/sprint')
      fixture.componentInstance['form'].controls.task.setValue('Some task')
      fixture.componentInstance['form'].controls.analyst.setValue('factory-analyst')
      fixture.componentInstance['form'].controls.editor.setValue('factory-editor')
      fixture.detectChanges()
      tick()
      const submitBtn = fixture.debugElement.query(By.css('button[type="submit"]'))
      expect(submitBtn.nativeElement.disabled).toBe(false)
    }))

    it('submit is DISABLED when rootError is set (roots mismatch)', fakeAsync(() => {
      switchToUsLoop()
      rootErrorSignal.set(
        'Root mismatch between analyst and editor: "factory-analyst" → /repo/sprint, "factory-editor" → /repo/coday.'
      )
      fixture.componentInstance['form'].controls.task.setValue('Some task')
      fixture.componentInstance['form'].controls.analyst.setValue('factory-analyst')
      fixture.componentInstance['form'].controls.editor.setValue('factory-editor')
      fixture.detectChanges()
      tick()
      const submitBtn = fixture.debugElement.query(By.css('button[type="submit"]'))
      expect(submitBtn.nativeElement.disabled).toBe(true)
    }))
  })

  describe('workflow change to backend-oracle-check', () => {
    function switchToBackendOracle() {
      const select = fixture.debugElement.query(By.css('#fl-workflow')).nativeElement as HTMLSelectElement
      select.value = 'backend-oracle-check'
      select.dispatchEvent(new Event('change'))
      fixture.detectChanges()
    }

    it('hides all agent fields', () => {
      switchToBackendOracle()
      expect(fixture.debugElement.query(By.css('label[for="fl-agent"]'))).toBeNull()
      expect(fixture.debugElement.query(By.css('label[for="fl-analyst"]'))).toBeNull()
      expect(fixture.debugElement.query(By.css('label[for="fl-editor"]'))).toBeNull()
    })

    it('submit is enabled with a task and a repository root', fakeAsync(() => {
      switchToBackendOracle()
      derivedRootSignal.set('/repo/backend') // backend-oracle-check also needs FACTORY_ROOT
      fixture.componentInstance['form'].controls.task.setValue('Some task')
      fixture.detectChanges()
      tick()
      expect(fixture.debugElement.query(By.css('button[type="submit"]')).nativeElement.disabled).toBe(false)
    }))

    it('submit is DISABLED with only a task and no root', fakeAsync(() => {
      switchToBackendOracle()
      derivedRootSignal.set(null)
      fixture.componentInstance['form'].controls.task.setValue('Some task')
      fixture.componentInstance['form'].controls.root.setValue('')
      fixture.detectChanges()
      tick()
      expect(fixture.debugElement.query(By.css('button[type="submit"]')).nativeElement.disabled).toBe(true)
    }))
  })

  describe('workflow change to agentos-smoke', () => {
    it('shows the single agent field', () => {
      const select = fixture.debugElement.query(By.css('#fl-workflow')).nativeElement as HTMLSelectElement
      select.value = 'agentos-smoke'
      select.dispatchEvent(new Event('change'))
      fixture.detectChanges()
      expect(fixture.debugElement.query(By.css('label[for="fl-agent"]'))).toBeTruthy()
    })
  })

  // ---------------------------------------------------------------------------
  // Agent select
  // ---------------------------------------------------------------------------

  describe('agent select populated from signal', () => {
    it('renders agent options when agents signal has items', () => {
      agentsSignal.set([{ name: 'factory-editor', enabled: true }])
      fixture.detectChanges()
      const options = fixture.debugElement.queryAll(By.css('#fl-agent option[value="factory-editor"]'))
      expect(options.length).toBeGreaterThan(0)
    })

    it('shows usable agents before other agents', () => {
      agentsSignal.set([
        { name: 'editor-a', enabled: true },
        { name: 'orchestrator', enabled: true, subAgents: ['sub-a'] },
      ])
      fixture.detectChanges()
      const usableOption = fixture.debugElement.query(By.css('#fl-agent option[value="editor-a"]'))
      expect(usableOption).toBeTruthy()
      expect(usableOption.parent?.nativeElement.tagName.toLowerCase()).not.toBe('optgroup')
    })
  })

  describe('manual fallback when agents fail', () => {
    it('shows an error hint when agentsError signal is set', () => {
      agentsErrorSignal.set('AgentOS unreachable \u2014 enter agent name manually.')
      fixture.detectChanges()
      const errorMsg = fixture.debugElement.query(By.css('.factory-launch__hint--warn'))
      expect(errorMsg).toBeTruthy()
      expect(errorMsg.nativeElement.textContent).toContain('AgentOS unreachable')
    })
  })

  // ---------------------------------------------------------------------------
  // us-loop form submission
  // ---------------------------------------------------------------------------

  describe('form submission for us-loop', () => {
    function switchToUsLoop() {
      const select = fixture.debugElement.query(By.css('#fl-workflow')).nativeElement as HTMLSelectElement
      select.value = 'us-loop'
      select.dispatchEvent(new Event('change'))
    }

    it('does not include FACTORY_AGENT in the launch request', fakeAsync(() => {
      switchToUsLoop()
      derivedRootSignal.set('/repo/sprint')
      fixture.componentInstance['form'].controls.task.setValue('Implement feature X')
      fixture.componentInstance['form'].controls.analyst.setValue('custom-analyst')
      fixture.componentInstance['form'].controls.editor.setValue('custom-editor')
      fixture.detectChanges()
      tick()

      fixture.componentInstance['submit']()

      expect(launchMock).toHaveBeenCalled()
      const request: FactoryLaunchRequest = launchMock.mock.calls[0][0]
      expect(request.workflow).toBe('us-loop')
      expect(request.FACTORY_AGENT).toBeUndefined()
    }))

    it('includes FACTORY_AGENT_ANALYST and FACTORY_AGENT_EDITOR when both are set', fakeAsync(() => {
      switchToUsLoop()
      derivedRootSignal.set('/repo/sprint')
      fixture.componentInstance['form'].controls.task.setValue('Implement feature X')
      fixture.componentInstance['form'].controls.analyst.setValue('custom-analyst')
      fixture.componentInstance['form'].controls.editor.setValue('custom-editor')
      fixture.detectChanges()
      tick()

      fixture.componentInstance['submit']()

      const request: FactoryLaunchRequest = launchMock.mock.calls[0][0]
      expect(request.FACTORY_AGENT_ANALYST).toBe('custom-analyst')
      expect(request.FACTORY_AGENT_EDITOR).toBe('custom-editor')
    }))

    it('sends FACTORY_ROOT from derivedRoot when roots match', fakeAsync(() => {
      switchToUsLoop()
      derivedRootSignal.set('/repo/sprint')
      fixture.componentInstance['form'].controls.root.setValue('/repo/sprint')
      fixture.componentInstance['form'].controls.task.setValue('Implement X')
      fixture.componentInstance['form'].controls.analyst.setValue('factory-analyst')
      fixture.componentInstance['form'].controls.editor.setValue('factory-editor')
      fixture.detectChanges()
      tick()

      fixture.componentInstance['submit']()

      const request: FactoryLaunchRequest = launchMock.mock.calls[0][0]
      expect(request.FACTORY_ROOT).toBe('/repo/sprint')
    }))
  })

  // ---------------------------------------------------------------------------
  // FACTORY_ROOT derivation and mismatch
  // ---------------------------------------------------------------------------

  describe('FACTORY_ROOT derivation and mismatch', () => {
    it('submit is disabled when rootMismatch is true (single-agent)', fakeAsync(() => {
      derivedRootSignal.set('/path/to/sprint')
      fixture.componentInstance['form'].controls.root.setValue('/different/path')
      fixture.componentInstance['form'].controls.task.setValue('Fix something')
      fixture.componentInstance['form'].controls.agent.setValue('factory-editor')
      fixture.detectChanges()
      tick()
      expect(fixture.debugElement.query(By.css('button[type="submit"]')).nativeElement.disabled).toBe(true)
    }))

    it('submit sends FACTORY_ROOT from derivedRoot when the field matches', fakeAsync(() => {
      derivedRootSignal.set('/path/to/sprint')
      fixture.componentInstance['form'].controls.root.setValue('/path/to/sprint')
      fixture.componentInstance['form'].controls.task.setValue('Fix something')
      fixture.componentInstance['form'].controls.agent.setValue('factory-editor')
      fixture.detectChanges()
      tick()

      fixture.componentInstance['submit']()

      const request: FactoryLaunchRequest = launchMock.mock.calls[0][0]
      expect(request.FACTORY_ROOT).toBe('/path/to/sprint')
    }))

    it('shows rootError hint when rootError signal is set', () => {
      rootErrorSignal.set(
        'Root mismatch between analyst and editor: "factory-analyst" → /repo/sprint, "factory-editor" → /repo/coday.'
      )
      fixture.detectChanges()
      const hints = fixture.debugElement.queryAll(By.css('.factory-launch__hint--warn'))
      const rootHint = hints.find((h) => h.nativeElement.textContent.includes('Root mismatch'))
      expect(rootHint).toBeTruthy()
    })

    it('shows root loading hint when rootLoading is true', () => {
      rootLoadingSignal.set(true)
      fixture.detectChanges()
      const statusHints = fixture.debugElement.queryAll(By.css('[role="status"]'))
      const rootHint = statusHints.find((h) => h.nativeElement.textContent.includes('Deriving repository root'))
      expect(rootHint).toBeTruthy()
    })
  })

  // ---------------------------------------------------------------------------
  // Namespace configPath as FACTORY_ROOT (A1)
  // ---------------------------------------------------------------------------

  describe('namespace configPath as FACTORY_ROOT source of truth', () => {
    it('shows namespace root info line with parent of configPath', () => {
      // configPath = /home/user/whoz/coday → namespaceRoot = /home/user/whoz
      fixture.componentRef.setInput('namespace', {
        id: 'ns-1',
        name: 'Whoz',
        configPath: '/home/user/whoz/coday',
      } as Namespace)
      fixture.detectChanges()
      const statusHints = fixture.debugElement.queryAll(By.css('[role="status"]'))
      const rootInfo = statusHints.find((h) => h.nativeElement.textContent.includes('/home/user/whoz'))
      expect(rootInfo).toBeTruthy()
    })

    it('does NOT include the configPath last segment in the displayed root', () => {
      fixture.componentRef.setInput('namespace', {
        id: 'ns-1',
        name: 'Whoz',
        configPath: '/home/user/whoz/coday',
      } as Namespace)
      fixture.detectChanges()
      const statusHints = fixture.debugElement.queryAll(By.css('[role="status"]'))
      const rootInfo = statusHints.find((h) => h.nativeElement.textContent.includes('/home/user/whoz'))
      expect(rootInfo?.nativeElement.textContent).not.toContain('/home/user/whoz/coday')
    })

    it('hides the root input field when namespace has configPath with a valid parent', () => {
      fixture.componentRef.setInput('namespace', {
        id: 'ns-1',
        name: 'Whoz',
        configPath: '/home/user/whoz/coday',
      } as Namespace)
      fixture.detectChanges()
      expect(fixture.debugElement.query(By.css('#fl-root'))).toBeNull()
    })

    it('canSubmit returns true when namespaceRoot (parent of configPath) is set and task is valid', fakeAsync(() => {
      fixture.componentRef.setInput('namespace', {
        id: 'ns-1',
        name: 'Whoz',
        configPath: '/home/user/whoz/coday',
      } as Namespace)
      fixture.componentInstance['form'].controls.task.setValue('Fix the bug')
      fixture.componentInstance['form'].controls.agent.setValue('factory-editor')
      fixture.detectChanges()
      tick()
      expect(fixture.componentInstance['canSubmit']()).toBe(true)
    }))

    it('canSubmit returns false when namespace has no configPath and no derivedRoot and no typed root', fakeAsync(() => {
      fixture.componentRef.setInput('namespace', { id: 'ns-1', name: 'Whoz' } as Namespace)
      derivedRootSignal.set(null)
      fixture.componentInstance['form'].controls.task.setValue('Fix the bug')
      fixture.componentInstance['form'].controls.agent.setValue('factory-editor')
      fixture.componentInstance['form'].controls.root.setValue('')
      fixture.detectChanges()
      tick()
      expect(fixture.componentInstance['canSubmit']()).toBe(false)
    }))

    it('uses parent of configPath as FACTORY_ROOT in submit payload (takes priority over derivedRoot)', fakeAsync(() => {
      // configPath = /home/user/whoz/coday → FACTORY_ROOT = /home/user/whoz
      fixture.componentRef.setInput('namespace', {
        id: 'ns-1',
        name: 'Whoz',
        configPath: '/home/user/whoz/coday',
      } as Namespace)
      derivedRootSignal.set('/other/path') // should be ignored
      fixture.componentInstance['form'].controls.task.setValue('Fix the bug')
      fixture.componentInstance['form'].controls.agent.setValue('factory-editor')
      fixture.detectChanges()
      tick()

      fixture.componentInstance['submit']()

      const request: FactoryLaunchRequest = launchMock.mock.calls[0][0]
      expect(request.FACTORY_ROOT).toBe('/home/user/whoz')
    }))

    it('shows warning when namespace has no configPath and no derivedRoot', fakeAsync(() => {
      fixture.componentRef.setInput('namespace', { id: 'ns-1', name: 'Whoz' } as Namespace)
      derivedRootSignal.set(null)
      fixture.componentInstance['form'].controls.agent.setValue('factory-editor')
      fixture.detectChanges()
      tick()
      const warnHints = fixture.debugElement.queryAll(By.css('.factory-launch__hint--warn'))
      const rootWarn = warnHints.find((h) => h.nativeElement.textContent.includes('No repository root'))
      expect(rootWarn).toBeTruthy()
    }))
  })
})
