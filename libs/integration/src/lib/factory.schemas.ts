export const statuses = [
  'pending',
  'ready',
  'running',
  'waiting_human',
  'blocked',
  'completed',
  'failed',
  'cancelled',
] as const
export const safeId = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
export const safeRuntimeId = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/
export const workflowLookupSchema = {
  type: 'object',
  additionalProperties: false,
  properties: { workflowId: { type: 'string', maxLength: 128, pattern: safeId.source } },
  required: ['workflowId'],
}
export const startSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    workflowId: { type: 'string', maxLength: 128, pattern: safeId.source },
    workflowType: { type: 'string', maxLength: 128 },
    title: { type: 'string', maxLength: 256 },
  },
  required: ['workflowId', 'workflowType', 'title'],
}
const evidenceBaseProperties = {
  workflowId: { type: 'string', maxLength: 128, pattern: safeId.source },
  stepId: { type: 'string', maxLength: 128, pattern: safeId.source },
  idempotencyKey: { type: 'string', maxLength: 128 },
}
export const agentResultEvidenceSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ...evidenceBaseProperties,
    outcome: { type: 'string', enum: ['pass', 'fail', 'indeterminate'] },
    facts: {
      type: 'object',
      additionalProperties: false,
      maxProperties: 32,
      properties: {
        resultCode: { type: 'string', maxLength: 256 },
        category: { type: 'string', maxLength: 256 },
        attempt: { type: 'integer' },
        durationMs: { type: 'integer' },
        itemCount: { type: 'integer' },
      },
    },
  },
  required: ['workflowId', 'stepId', 'facts'],
}
export const artifactEvidenceSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ...evidenceBaseProperties,
    artifactRef: { type: 'string', maxLength: 1024 },
    artifactHash: { type: 'string', pattern: '^sha256:[0-9a-f]{64}$' },
  },
  required: ['workflowId', 'stepId', 'artifactRef', 'artifactHash'],
}
export const projectionSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    schemaVersion: { type: 'string', enum: ['1', '2'] },
    workflowId: { type: 'string', maxLength: 128 },
    workflowType: { type: 'string', maxLength: 256 },
    title: { type: 'string', maxLength: 256 },
    status: { type: 'string', enum: statuses },
    expectedRevision: { type: 'integer', minimum: 0 },
    steps: {
      type: 'array',
      maxItems: 500,
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          id: { type: 'string', maxLength: 128 },
          name: { type: 'string', maxLength: 256 },
          status: { type: 'string', enum: statuses },
          description: { type: 'string', maxLength: 4096 },
          dependsOn: { type: 'array', maxItems: 100, items: { type: 'string', maxLength: 128 } },
          responsibility: {
            type: 'object',
            additionalProperties: false,
            properties: {
              kind: { type: 'string', enum: ['human', 'agent', 'code'] },
              name: { type: 'string', maxLength: 256 },
            },
            required: ['kind'],
          },
        },
        required: ['id', 'name', 'status'],
      },
    },
  },
  required: ['schemaVersion', 'workflowId', 'workflowType', 'title', 'status', 'steps'],
}
