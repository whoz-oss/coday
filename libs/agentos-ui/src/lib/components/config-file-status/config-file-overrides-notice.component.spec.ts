import { TestBed } from '@angular/core/testing'
import { ConfigFile, ConfigFileKindEnum, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import { ConfigFileOverridesNoticeComponent } from './config-file-overrides-notice.component'

describe('ConfigFileOverridesNoticeComponent', () => {
  const overridden = (name: string): ConfigFile => ({
    kind: ConfigFileKindEnum.AGENT,
    name,
    path: `agents/${name}.yaml`,
    configId: name,
    state: ConfigFileStateEnum.OVERRIDDEN,
    modified: true,
  })

  function render(files: ConfigFile[]): string {
    const fixture = TestBed.createComponent(ConfigFileOverridesNoticeComponent)
    fixture.componentRef.setInput('overridden', files)
    fixture.componentRef.setInput('noun', 'agent config')
    fixture.detectChanges()
    return (fixture.nativeElement as HTMLElement).textContent?.trim() ?? ''
  }

  it('names the configs that mask their files', () => {
    expect(render([overridden('coday')])).toContain('1 agent config of this namespace overrides its file (coday)')
    expect(render([overridden('coday'), overridden('reviewer')])).toContain(
      '2 agent configs of this namespace override their files (coday, reviewer)'
    )
  })

  it('stays hidden when no config masks a file', () => {
    expect(render([])).toBe('')
  })
})
