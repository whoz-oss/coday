import { Injector, runInInjectionContext } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import {
  ConfigFile,
  ConfigFileControllerService,
  ConfigFileKindEnum,
  ConfigFileStateEnum,
} from '@whoz-oss/agentos-api-client'
import { of, Subject, throwError } from 'rxjs'
import { injectConfigFileStatuses } from './config-files.service'

describe('injectConfigFileStatuses', () => {
  const file = (kind: ConfigFileKindEnum, configId: string, state: ConfigFileStateEnum): ConfigFile => ({
    kind,
    name: configId,
    path: `${kind.toLowerCase()}s/${configId}.yaml`,
    configId,
    state,
  })
  const files = [
    file(ConfigFileKindEnum.AGENT, 'from-file', ConfigFileStateEnum.FILE),
    file(ConfigFileKindEnum.AGENT, 'copy', ConfigFileStateEnum.OVERRIDDEN),
    file(ConfigFileKindEnum.PROMPT, 'prompt-copy', ConfigFileStateEnum.OVERRIDDEN),
  ]

  function setup(listConfigFiles: jest.Mock) {
    TestBed.configureTestingModule({
      providers: [{ provide: ConfigFileControllerService, useValue: { listConfigFiles } }],
    })
    return TestBed.inject(Injector)
  }

  it('keeps the entries of one kind, by the id the lists show', () => {
    const injector = setup(jest.fn().mockReturnValue(of(files)))
    const statuses = runInInjectionContext(injector, () => injectConfigFileStatuses('ns', ConfigFileKindEnum.AGENT))

    expect(statuses.of('from-file')?.state).toBe(ConfigFileStateEnum.FILE)
    expect(statuses.of('prompt-copy')).toBeUndefined()
    expect(statuses.of(undefined)).toBeUndefined()
    expect(statuses.overridden().map((entry) => entry.configId)).toEqual(['copy'])
    expect(statuses.any()).toBe(true)
  })

  it('reloads each time it is asked to', () => {
    const listConfigFiles = jest.fn().mockReturnValue(of(files))
    const reload$ = new Subject<void>()
    runInInjectionContext(setup(listConfigFiles), () =>
      injectConfigFileStatuses('ns', ConfigFileKindEnum.AGENT, reload$)
    )

    reload$.next()
    reload$.next()

    expect(listConfigFiles).toHaveBeenCalledTimes(2)
  })

  it('treats a failed read as a namespace without config files', () => {
    const injector = setup(jest.fn().mockReturnValue(throwError(() => new Error('403'))))
    const statuses = runInInjectionContext(injector, () => injectConfigFileStatuses('ns', ConfigFileKindEnum.AGENT))

    expect(statuses.any()).toBe(false)
    expect(statuses.overridden()).toEqual([])
  })
})
