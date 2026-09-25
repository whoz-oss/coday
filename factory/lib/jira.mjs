// Stateless compatibility facade. The Jira helpers live only in the generated
// operational bundle, built from the TypeScript sources
// `factory/src/domain/forge-bmad/jira.ts` and
// `factory/src/adapters/jira/jira-client.ts`.
export {
  extractTicketId,
  extractAdfText,
  fetchJiraComments,
  applyCommentBudget,
  fetchJiraTicket,
} from '../runtime/factory-operational.mjs'
