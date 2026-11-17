import { TestBed } from '@angular/core/testing'
import { MatDialog } from '@angular/material/dialog'
import { ConfigFile, ConfigFileKindEnum, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { ConfigFilesService } from '../../services/config-files.service'
import { ConfigFileBannerComponent } from './config-file-banner.component'
import { ConfigFileDiffComponent } from './config-file-diff.component'

describe('ConfigFileBannerComponent', () => {
  const files: ConfigFile[] = [
    {
      kind: ConfigFileKindEnum.AGENT,
      name: 'coday',
      path: 'agents/coday.yaml',
      configId: 'copy',
      state: ConfigFileStateEnum.OVERRIDDEN,
      modified: true,
    },
    {
      kind: ConfigFileKindEnum.AGENT,
      name: 'reviewer',
      path: 'agents/reviewer.yaml',
      configId: 'file',
      state: ConfigFileStateEnum.FILE,
    },
  ]
  const open = jest.fn()

  async function render(configId: string) {
    await TestBed.configureTestingModule({
      imports: [ConfigFileBannerComponent],
      providers: [
        { provide: ConfigFilesService, useValue: { list: jest.fn().mockReturnValue(of(files)) } },
        { provide: MatDialog, useValue: { open } },
      ],
    }).compileComponents()
    const fixture = TestBed.createComponent(ConfigFileBannerComponent)
    fixture.componentRef.setInput('namespaceId', 'ns')
    fixture.componentRef.setInput('kind', ConfigFileKindEnum.AGENT)
    fixture.componentRef.setInput('configId', configId)
    fixture.componentRef.setInput('noun', 'agent config')
    fixture.detectChanges()
    return fixture
  }

  beforeEach(() => open.mockReset())

  it('says which file a database copy masks, and opens the differences', async () => {
    const fixture = await render('copy')
    const element = fixture.nativeElement as HTMLElement

    expect(element.textContent).toContain('This agent config overrides the file agents/coday.yaml')
    expect(element.textContent).toContain('It differs from the file.')
    element.querySelector('button')?.click()
    await fixture.whenStable()

    expect(open).toHaveBeenCalledWith(
      ConfigFileDiffComponent,
      expect.objectContaining({ data: { kind: ConfigFileKindEnum.AGENT, id: 'copy', name: 'coday' } })
    )
  })

  it('says nothing for a config read from its file', async () => {
    const fixture = await render('file')

    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('')
  })
})
