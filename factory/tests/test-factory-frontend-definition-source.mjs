import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { validateWorkflowDefinition } from '../lib/workflow-definition.mjs'
const input=JSON.parse(await readFile(new URL('../workflows/bmad-story-frontend/1.0.0.json',import.meta.url),'utf8')),validated=validateWorkflowDefinition(input)
assert.equal(validated.ok,true);assert.equal(validated.definition.workflowType,'bmad-story-frontend');assert.deepEqual(validated.definition.steps.map((s)=>s.id),['ticket-analysis','intent-checkpoint','product-specification','product-checkpoint','ux-design','codebase-research','ux-checkpoint','technical-design','technical-checkpoint','frontend-implementation','frontend-verification','technical-review'])
assert.deepEqual(input.trustedExecution.allowedPaths,['frontend/apps','frontend/libs','tsconfig.base.json'])
assert.deepEqual(validated.definition.trustedExecution.allowedPaths,input.trustedExecution.allowedPaths)
assert.equal(JSON.stringify(input).includes('apps/client'),false)
assert.deepEqual([...new Set(input.steps.filter((step)=>step.responsibility.kind==='agent').map((step)=>step.responsibility.name))],['ForgeProductWorker','ForgeUXDesigner','Searcher','ForgeTechDesigner','ForgeFrontendWorker','ForgeReviewer'])
