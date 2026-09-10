import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun, parseForgeLedger, projectForgeRun } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash, G1_POLICY_VERSION, recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { evaluateG2 } from '../lib/forge-g2.mjs'
import { executeStoryAnalysis } from '../lib/forge-story-analysis.mjs'
const root=mkdtempSync(join(tmpdir(),'story-analysis-')); const factory=join(root,'factory'), repo=join(root,'repo'), specs=join(repo,'forge','specs'); mkdirSync(factory); mkdirSync(specs,{recursive:true})
const roots=resolveForgeRoots({orchestratorRoot:factory,runStoreRoot:join(factory,'runs'),repoRoot:repo,forgeRoot:join(repo,'forge')}); const spec=join(specs,'x.md'); writeFileSync(spec,'---\nschemaVersion: 1\nworkItem:\n  id: E\n  kind: Epic\nscope:\n  allow:\n    - apps/*\n  create:\n    - libs/**\n  deny:\n    - secrets/**\noracles:\n  - front.build\n---\n')
const run=createEpicRun({roots,runId:'epic_analysis',epic:{id:'E',kind:'Epic'},stories:[{id:'S',kind:'Story'}]}); const storyId=run.storyRuns[0].runId
const fake={preflightAgent:async()=>({ok:true,agent:{integrations:{FACTORY_FILES_RO:null,QUERY_USER:[]}}}),preflightReadOnlyWorkspace:async()=>({ok:true,rootPath:repo}),createCase:async()=>({id:'case-1'}),runAgentTurn:async()=>({status:'finished',caseStatus:'IDLE',message:'LLM prose must stay out',killedByBudget:false})}
await assert.rejects(()=>executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:storyId,namespaceId:'ns',agentName:'analyst',brief:'read only',runtime:fake}),/G1_NOT_APPROVED/)
let events=parseForgeLedger(run.filePath); await recordHumanDecision({roots,runId:run.runId,decision:{gate:'G1',attempt:1,policyVersion:G1_POLICY_VERSION,evidenceSetHash:computeG1EvidenceSetHash(events,run.runId),outcome:'approved',reasonCode:'intent_confirmed'},identityPort:{actorId:async()=> 'human',authorize:async()=>({authorityId:'owner'})}})
await assert.rejects(()=>executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:storyId,namespaceId:'ns',agentName:'analyst',brief:'read only',runtime:fake}),/G2_NOT_PASSED/)
evaluateG2({roots,runId:run.runId,specPath:spec}); const hash=parseForgeLedger(run.filePath).filter(e=>e.event==='g2_evaluated').at(-1).spec.sha256
const result=await executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:storyId,namespaceId:'ns',agentName:'analyst',brief:'read only',expectedSpecHash:hash,runtime:fake}); assert.equal(result.outcome,'finished')
events=parseForgeLedger(run.filePath); const started=events.find(e=>e.event==='agent_execution_started'), ended=events.find(e=>e.event==='agent_execution_finished'); assert.equal(started.caseId,'case-1'); assert.equal(ended.storyRunId,storyId); assert.equal(JSON.stringify(ended).includes('LLM prose must stay out'),false); assert.equal(projectForgeRun(events).stories[0].executions[0].outcome,'finished'); assert.equal(projectForgeRun(events).stories[0].executions[0].caseStatus,'IDLE'); assert.equal(projectForgeRun(events).stories[0].executions[0].killedByBudget,false)
const unsafe={...fake,preflightReadOnlyWorkspace:async()=>({ok:false,reason:'readOnly:false'})}; await assert.rejects(()=>executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:storyId,namespaceId:'ns',agentName:'analyst',brief:'read only',expectedSpecHash:hash,runtime:unsafe}),/READ_ONLY_PREFLIGHT_FAILED/)
const failing={...fake,runAgentTurn:async()=>({status:'work_timeout',caseStatus:null,killedByBudget:true,message:'secret prose'})}; const failed=await executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:storyId,namespaceId:'ns',agentName:'analyst',brief:'read only',expectedSpecHash:hash,runtime:failing}); assert.equal(failed.execution.status,'failed')
console.log('forge story analysis: ok')
