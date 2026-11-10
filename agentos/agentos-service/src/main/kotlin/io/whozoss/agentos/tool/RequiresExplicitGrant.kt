package io.whozoss.agentos.tool

/**
 * Marks a tool that an agent receives only when its integration grant **names** it
 * (`CASE: [BatchCaseStarter]`). A grant without a tool list (`CASE: null`, or an auto-granted
 * integration) gives every other tool of the integration, but not this one.
 *
 * For tools meant for backend workflows rather than for an LLM choosing freely: being part of an
 * integration must not be enough to receive them.
 */
interface RequiresExplicitGrant
