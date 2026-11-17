import { HttpErrorResponse } from '@angular/common/http'
import { TestBed } from '@angular/core/testing'
import {
  ConfigFile,
  ConfigFileDiff,
  ConfigFileKindEnum,
  ConfigFileStateEnum,
  ConfigFileWriteResult,
} from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { ConfigFilesService } from '../../services/config-files.service'
import { ConfigFilePanelComponent } from './config-file-panel.component'

describe('ConfigFilePanelComponent', () => {
  const entry = (state: ConfigFileStateEnum, modified?: boolean): ConfigFile => ({
    kind: ConfigFileKindEnum.AGENT,
    name: 'coday',
    path: 'agents/coday.yaml',
    configId: 'copy',
    state,
    modified,
  })
  const diff = (overrides: Partial<ConfigFileDiff> = {}): ConfigFileDiff => ({
    kind: ConfigFileKindEnum.AGENT,
    name: 'coday',
    configId: 'copy',
    path: 'agents/coday.yaml',
    modified: true,
    files: [
      {
        path: 'agents/coday.yaml',
        fileContent: 'name: coday\nintegrations:\n  JIRA: null\n',
        configContent: 'name: coday\ninstructions: Answer in French.\n',
      },
    ],
    notes: [],
    fileHash: 'hash',
    alsoReadBy: [],
    cannotWrite: [],
    cannotDiscard: [],
    ...overrides,
  })
  const deleted: ConfigFileWriteResult = { path: 'agents/coday.yaml', written: true, copyDeleted: true }

  async function render(
    file: ConfigFile,
    service: Partial<Record<keyof ConfigFilesService, jest.Mock>> = {},
    extra = {}
  ) {
    await TestBed.configureTestingModule({
      imports: [ConfigFilePanelComponent],
      providers: [
        {
          provide: ConfigFilesService,
          useValue: {
            diff: jest.fn().mockReturnValue(of(diff())),
            writeToFile: jest.fn(),
            discard: jest.fn(),
            ...service,
          },
        },
      ],
    }).compileComponents()
    const fixture = TestBed.createComponent(ConfigFilePanelComponent)
    fixture.componentRef.setInput('file', file)
    fixture.componentRef.setInput('noun', 'agent config')
    Object.entries(extra).forEach(([name, value]) => fixture.componentRef.setInput(name, value))
    const settled = jest.fn()
    fixture.componentInstance.settled.subscribe(settled)
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    const element = fixture.nativeElement as HTMLElement
    const button = (label: string) =>
      Array.from(element.querySelectorAll('button')).find(
        (node) => node.textContent?.trim() === label
      ) as HTMLButtonElement
    return { fixture, element, settled, button }
  }

  it('warns, on a config read from its file, that saving it creates a copy that masks the file', async () => {
    const { element } = await render(entry(ConfigFileStateEnum.FILE), {}, { savingCreatesCopy: true })

    expect(element.textContent).toContain('This agent config is read from agents/coday.yaml.')
    expect(element.querySelector('ds-card')).toBeNull()
  })

  it('shows how an out of sync copy differs from its file, lines of the file in − and of the copy in +', async () => {
    const { element, button } = await render(entry(ConfigFileStateEnum.OVERRIDDEN, true))

    expect(element.textContent).toContain('AgentOS uses this database copy and ignores agents/coday.yaml.')
    const rows = Array.from(element.querySelectorAll('.config-file-panel__row')).map((row) => row.textContent)
    expect(rows).toContain('− integrations:')
    expect(rows).toContain('+ instructions: Answer in French.')
    expect(button('Write to file').disabled).toBe(false)
    expect(button('Download file')).toBeTruthy()
    expect(button('Discard this copy')).toBeTruthy()
  })

  it('writes the copy to its file once confirmed, with the hash of the file it showed', async () => {
    const writeToFile = jest.fn().mockReturnValue(of(deleted))
    const { fixture, element, settled, button } = await render(entry(ConfigFileStateEnum.OVERRIDDEN, true), {
      writeToFile,
    })

    button('Write to file').click()
    fixture.detectChanges()
    expect(element.querySelector('[role="dialog"]')?.textContent).toContain(
      'Write this agent config to agents/coday.yaml?'
    )
    ;(element.querySelector('.ds-confirm-dialog__button--confirm') as HTMLButtonElement).click()

    expect(writeToFile).toHaveBeenCalledWith(ConfigFileKindEnum.AGENT, 'copy', 'hash')
    expect(settled).toHaveBeenCalled()
  })

  it('says why a write failed and keeps the download at hand', async () => {
    const writeToFile = jest.fn().mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 422,
            error: { message: "AgentOS is not allowed to write in agents of this namespace's config folder." },
          })
      )
    )
    const { fixture, element, settled, button } = await render(entry(ConfigFileStateEnum.OVERRIDDEN, true), {
      writeToFile,
    })

    button('Write to file').click()
    fixture.detectChanges()
    ;(element.querySelector('.ds-confirm-dialog__button--confirm') as HTMLButtonElement).click()
    fixture.detectChanges()

    expect(element.textContent).toContain(
      "AgentOS is not allowed to write in agents of this namespace's config folder."
    )
    expect(element.textContent).toContain('You can download the file and put it in place yourself.')
    expect(settled).not.toHaveBeenCalled()
  })

  it('disables the write, and says why, when the copy holds what a file cannot', async () => {
    const reason = 'The parameter token holds a credential in clear.'
    const { element, button } = await render(entry(ConfigFileStateEnum.OVERRIDDEN, true), {
      diff: jest.fn().mockReturnValue(of(diff({ cannotWrite: [reason] }))),
    })

    expect(element.textContent).toContain(reason)
    expect(button('Write to file').disabled).toBe(true)
    expect(button('Discard this copy').disabled).toBe(false)
  })

  it('offers only to use the file again when the copy says the same', async () => {
    const discard = jest.fn().mockReturnValue(of({ ...deleted, written: false }))
    const { fixture, element, settled, button } = await render(entry(ConfigFileStateEnum.OVERRIDDEN, false), {
      diff: jest.fn().mockReturnValue(of(diff({ modified: false, files: [] }))),
      discard,
    })

    expect(element.textContent).toContain('This copy says the same as the file.')
    expect(button('Write to file')).toBeUndefined()
    button('Use the file again').click()
    fixture.detectChanges()
    ;(element.querySelector('.ds-confirm-dialog__button--confirm') as HTMLButtonElement).click()

    expect(discard).toHaveBeenCalledWith(ConfigFileKindEnum.AGENT, 'copy')
    expect(settled).toHaveBeenCalled()
  })
})
