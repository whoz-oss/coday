/**
 * Aggregation of simple integrations and those that are grouped
 * Example: AI integration depends on having at least one of the AI provider integration defined.
 */
export const Integrations: Record<string, string[]> = {
  CONFLUENCE: [],
  GIT: [],
  GITLAB: [],
  JIRA: [],
  ZENDESK_ARTICLES: [],
}

export const ConcreteIntegrations: string[] = Object.keys(Integrations).filter((k) => Integrations[k]?.length === 0)
