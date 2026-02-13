/**
 * Prompt model - Reusable AI task definition
 *
 * Prompts are parameterized AI tasks that can be executed:
 * - Directly by users in the UI
 * - Via schedulers (automated execution)
 * - Via webhooks (external API calls, requires CODAY_ADMIN)
 *
 * Replaces the previous "Webhook" concept with a more generic task definition.
 */

/**
 * Source location for prompt storage
 * - 'local': Stored in ~/.coday/projects/{projectName}/prompts/ (user-specific, not committed)
 * - 'project': Stored in {projectPath}/prompts/ (next to coday.yaml, committable)
 */
export type PromptSource = 'local' | 'project'

export interface Prompt {
  /**
   * Unique identifier (UUID v4)
   */
  id: string

  /**
   * Human-readable name
   */
  name: string

  /**
   * Description of what this prompt does
   */
  description: string

  /**
   * Template commands with placeholder substitution
   * Can contain placeholders like {{paramName}} for substitution
   * Example: "Review PR {{prNumber}} in project {{project}}"
   */
  commands: string[]

  /**
   * Webhook activation flag (requires CODAY_ADMIN to modify)
   * When true, this prompt can be triggered via external HTTP API
   */
  webhookEnabled: boolean

  /**
   * User who created this prompt
   */
  createdBy: string

  /**
   * Creation timestamp (ISO 8601)
   */
  createdAt: string

  /**
   * Last update timestamp (ISO 8601)
   */
  updatedAt?: string

  /**
   * Storage location for this prompt
   * - 'local': Personal prompt in ~/.coday/projects/{projectName}/prompts/
   * - 'project': Shared prompt in {projectPath}/prompts/ (committable)
   *
   * Set at creation time and immutable afterwards.
   * If editing is needed in a different location, user must duplicate manually.
   */
  source: PromptSource

  /**
   * Parameter format for autocomplete (computed from commands)
   * - undefined: No parameters expected
   * - "" (empty string): Single trailing parameter
   * - "key1=\"\" key2=\"\"": Structured parameters
   *
   * Automatically computed when prompt is created or commands are updated.
   */
  parameterFormat?: string
}

/**
 * Prompt info for listing (subset of Prompt)
 */
export interface PromptInfo {
  id: string
  name: string
  description: string
  webhookEnabled: boolean
  createdBy: string
  createdAt: string
  updatedAt?: string
  source: PromptSource
  /**
   * Parameter format for autocomplete
   * - undefined: No parameters expected
   * - "" (empty string): Single trailing parameter
   * - "key1=\"\" key2=\"\"": Structured parameters
   */
  parameterFormat?: string
}
