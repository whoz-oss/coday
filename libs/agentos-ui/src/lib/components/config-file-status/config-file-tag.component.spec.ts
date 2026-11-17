import { TestBed } from '@angular/core/testing'
import { ConfigFile, ConfigFileKindEnum, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import { ConfigFileTagComponent } from './config-file-tag.component'

describe('ConfigFileTagComponent', () => {
  const entry = (state: ConfigFileStateEnum, modified?: boolean): ConfigFile => ({
    kind: ConfigFileKindEnum.AGENT,
    name: 'coday',
    path: 'agents/coday.yaml',
    configId: 'id',
    state,
    modified,
  })

  function render(inputs: { file?: ConfigFile; showDatabase?: boolean; fileLabel?: string }): string {
    const fixture = TestBed.createComponent(ConfigFileTagComponent)
    Object.entries(inputs).forEach(([name, value]) => fixture.componentRef.setInput(name, value))
    fixture.detectChanges()
    return (fixture.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ').trim() ?? ''
  }

  it('names the file a config is read from', () => {
    expect(render({ file: entry(ConfigFileStateEnum.FILE) })).toBe('YAML')
    expect(render({ file: entry(ConfigFileStateEnum.FILE), fileLabel: 'SKILL.md' })).toBe('SKILL.md')
  })

  it('says when a database copy masks its file, and whether it differs from it', () => {
    expect(render({ file: entry(ConfigFileStateEnum.OVERRIDDEN, true) })).toBe('Overrides YAML · modified')
    expect(render({ file: entry(ConfigFileStateEnum.OVERRIDDEN, false) })).toBe('Overrides YAML · identical')
  })

  it('says Database only for a namespace that has config files', () => {
    expect(render({ showDatabase: true })).toBe('Database')
    expect(render({ showDatabase: false })).toBe('')
  })
})
