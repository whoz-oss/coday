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

  it('adds Out of sync to a database copy that differs from its file, Overridden to one that does not', () => {
    expect(render({ file: entry(ConfigFileStateEnum.OVERRIDDEN, true) })).toBe('YAML Out of sync')
    expect(render({ file: entry(ConfigFileStateEnum.OVERRIDDEN, false) })).toBe('YAML Overridden')
  })

  it('marks an out of sync copy with the warning tag', () => {
    const fixture = TestBed.createComponent(ConfigFileTagComponent)
    fixture.componentRef.setInput('file', entry(ConfigFileStateEnum.OVERRIDDEN, true))
    fixture.detectChanges()

    const tags = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('ds-tag'))
    expect(tags.map((tag) => tag.className)).toEqual(['tag tag--neutral', 'tag tag--warning'])
  })

  it('says Database only for a namespace that has config files', () => {
    expect(render({ showDatabase: true })).toBe('Database')
    expect(render({ showDatabase: false })).toBe('')
  })
})
