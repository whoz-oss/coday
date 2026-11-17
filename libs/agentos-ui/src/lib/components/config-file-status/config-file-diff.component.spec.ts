import { TestBed } from '@angular/core/testing'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { ConfigFileDiff, ConfigFileKindEnum } from '@whoz-oss/agentos-api-client'
import { of } from 'rxjs'
import { ConfigFilesService } from '../../services/config-files.service'
import { ConfigFileDiffComponent } from './config-file-diff.component'

describe('ConfigFileDiffComponent', () => {
  async function render(result: ConfigFileDiff) {
    const diff = jest.fn().mockReturnValue(of(result))
    await TestBed.configureTestingModule({
      imports: [ConfigFileDiffComponent],
      providers: [
        { provide: ConfigFilesService, useValue: { diff } },
        { provide: MatDialogRef, useValue: { close: jest.fn() } },
        { provide: MAT_DIALOG_DATA, useValue: { kind: ConfigFileKindEnum.SKILL, id: 'copy', name: 'review' } },
      ],
    }).compileComponents()
    const fixture = TestBed.createComponent(ConfigFileDiffComponent)
    fixture.detectChanges()
    await fixture.whenStable()
    fixture.detectChanges()
    return { fixture, diff, element: fixture.nativeElement as HTMLElement }
  }

  const skillDiff: ConfigFileDiff = {
    kind: ConfigFileKindEnum.SKILL,
    name: 'review',
    configId: 'copy',
    path: 'skills/review/SKILL.md',
    modified: true,
    notes: [],
    files: [
      { path: 'skills/review/SKILL.md', fileContent: 'same\n', configContent: 'same\n' },
      { path: 'skills/review/checklist.md', fileContent: '<img src=x onerror=alert(1)>\n', configContent: null },
      { path: 'skills/review/template.md', fileContent: null, configContent: '# Review\n' },
    ],
  }

  it('opens on the first file that differs, and lists every file with its status', async () => {
    const { diff, element } = await render(skillDiff)

    expect(diff).toHaveBeenCalledWith(ConfigFileKindEnum.SKILL, 'copy')
    expect(element.querySelector('main h3')?.textContent).toBe('skills/review/checklist.md')
    const statuses = Array.from(element.querySelectorAll('nav button small')).map((node) => node.textContent)
    expect(statuses).toEqual(['Identical', 'Only in the file', 'Only in the database copy'])
  })

  it('renders file contents as text, never as markup', async () => {
    const { element } = await render(skillDiff)

    expect(element.querySelector('.diff-content img')).toBeNull()
    expect(element.querySelector('.diff-content')?.textContent).toContain('<img src=x onerror=alert(1)>')
  })

  it('says when the copy is identical, and shows what its file cannot hold', async () => {
    const { element } = await render({
      kind: ConfigFileKindEnum.PROMPT,
      name: 'plan',
      configId: 'copy',
      path: 'prompts/plan.yaml',
      modified: false,
      notes: ['A prompt file cannot target an agent.'],
      files: [{ path: 'prompts/plan.yaml', fileContent: 'name: plan\n', configContent: 'name: plan\n' }],
    })

    expect(element.textContent).toContain('The database copy is identical to the file.')
    expect(element.textContent).toContain('A prompt file cannot target an agent.')
    expect(element.querySelector('nav')).toBeNull()
  })
})
