import { IntegrationConfig, IntegrationConfigSourceEnum } from '@whoz-oss/agentos-api-client'

/**
 * Where an integration configuration comes from, as the templates compare it. A string union rather than the
 * generated enum: a template comparing an enum with a literal fails `strictTemplates`.
 */
export type IntegrationConfigSource = 'NEO4J' | 'FILESYSTEM'

/**
 * The origin of a configuration. FILESYSTEM entries are read-only YAML files of the namespace; everything else is
 * stored in AgentOS (`NEO4J`), which is also what a server that does not send the field means.
 */
export function sourceOf(config: Pick<IntegrationConfig, 'source'>): IntegrationConfigSource {
  return config.source === IntegrationConfigSourceEnum.FILESYSTEM ? 'FILESYSTEM' : 'NEO4J'
}
