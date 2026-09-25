// Stateless compatibility facade. Agent step execution, structured result
// parsing, inline artifact materialization and the AgentOS result binding
// (`bindFactoryStepResult`) live only in the generated operational bundle, built
// from the TypeScript source
// `factory/src/application/agent-attempt/factory-agent-step-executor.ts`.
export {
  artifactEvidenceIdempotencyKey,
  parseAgentStepResult,
  materializeInlineArtifact,
  executeAgentStepAttempt,
  hashAgentBrief,
  hashStructuredAgentResult,
} from '../runtime/factory-operational.mjs'
