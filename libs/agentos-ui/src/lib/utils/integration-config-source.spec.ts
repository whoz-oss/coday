import { IntegrationConfigSourceEnum } from '@whoz-oss/agentos-api-client'
import { sourceOf } from './integration-config-source'

describe('sourceOf', () => {
  it('tells a YAML file of the namespace from a stored configuration', () => {
    expect(sourceOf({ source: IntegrationConfigSourceEnum.FILESYSTEM })).toBe('FILESYSTEM')
    expect(sourceOf({ source: IntegrationConfigSourceEnum.NEO4J })).toBe('NEO4J')
  })

  it('reads a configuration without a source as stored, so it stays editable', () => {
    expect(sourceOf({} as { source: IntegrationConfigSourceEnum })).toBe('NEO4J')
  })
})
