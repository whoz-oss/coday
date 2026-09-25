import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveForgeRoots } from '../lib/forge-roots.mjs'
import { createEpicRun, parseForgeLedger, projectForgeRun } from '../lib/forge-ledger.mjs'
import { computeG1EvidenceSetHash, G1_POLICY_VERSION, recordHumanDecision } from '../lib/forge-human-decision.mjs'
import { evaluateG2 } from '../lib/forge-g2.mjs'
import { executeStoryAnalysis, writeStoryAnalysisArtifact } from '../lib/forge-story-analysis.mjs'

const root=mkdtempSync(join(tmpdir(),'analysis-edge-')), factory=join(root,'factory'), repo=join(root,'repo'), specs=join(repo,'forge','specs'); mkdirSync(factory); mkdirSync(specs,{recursive:true}); mkdirSync(join(repo,'apps','private'),{recursive:true}); writeFileSync(join(repo,'apps','ok.ts'),'x'); writeFileSync(join(repo,'apps','private','no.ts'),'x')
const roots=resolveForgeRoots({orchestratorRoot:factory,runStoreRoot:join(factory,'runs'),repoRoot:repo,forgeRoot:join(repo,'forge')}); const spec=join(specs,'s.md'); writeFileSync(spec,'---\nschemaVersion: 1\nworkItem:\n  id: E\n  kind: Epic\nscope:\n  allow:\n    - apps/**\n  create:\n    - new/**\n  deny:\n    - apps/private/**\noracles:\n  - front.build\n---\n')
const run=createEpicRun({roots,runId:'epic_edge',epic:{id:'E',kind:'Epic'},stories:[{id:'S',kind:'Story'}]}); const story=run.storyRuns[0].runId; let events=parseForgeLedger(run.filePath); await recordHumanDecision({roots,runId:run.runId,decision:{gate:'G1',attempt:1,policyVersion:G1_POLICY_VERSION,evidenceSetHash:computeG1EvidenceSetHash(events,run.runId),outcome:'approved',reasonCode:'intent_confirmed'},identityPort:{actorId:async()=> 'h',authorize:async()=>({authorityId:'o'})}}); evaluateG2({roots,runId:run.runId,specPath:spec}); const hash=parseForgeLedger(run.filePath).filter(e=>e.event==='g2_evaluated').at(-1).spec.sha256
let creates=0, message=''; const runtime={preflightAgent:async()=>({ok:true,agent:{}}),preflightReadOnlyWorkspace:async()=>({ok:true}),createCase:async()=>({id:`case-${++creates}`}),runAgentTurn:async()=>({status:'finished',caseStatus:'IDLE',killedByBudget:false,message})}; const invoke=()=>executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:story,namespaceId:'n',agentName:'a',expectedSpecHash:hash,runtime})
message='```json\n{"files":["apps/private/no.ts"],"doneWhen":"x"}\n```'; assert.equal((await invoke()).validation.code,'STORY_ANALYSIS_PLAN_OUT_OF_SCOPE')
message=`\`\`\`json\n${JSON.stringify({files:Array(31).fill('apps/ok.ts'),doneWhen:'x'})}\n\`\`\``; assert.equal((await invoke()).validation.code,'STORY_ANALYSIS_PLAN_LIMIT')
message=`\`\`\`json\n${JSON.stringify({files:['apps/ok.ts'],doneWhen:'x'.repeat(4001)})}\n\`\`\``; assert.equal((await invoke()).validation.code,'STORY_ANALYSIS_PLAN_LIMIT')
message=`\`\`\`json\n${JSON.stringify({files:['apps/ok.ts'],doneWhen:'x',steps:['x'.repeat(4001)]})}\n\`\`\``; assert.equal((await invoke()).validation.code,'STORY_ANALYSIS_PLAN_LIMIT')
const killedCreates=creates; runtime.runAgentTurn=async()=>({status:'killed',caseStatus:'KILLED',killedByBudget:false,message:''}); await invoke(); message='```json\n{"files":["apps/ok.ts"],"doneWhen":"x"}\n```'; runtime.runAgentTurn=async()=>({status:'finished',caseStatus:'IDLE',killedByBudget:false,message}); await invoke(); assert.ok(creates>=killedCreates+2,'KILLED execution must be terminal and relaunchable')
events=parseForgeLedger(run.filePath); const latest=projectForgeRun(events).stories[0].executions.at(-1); assert.equal(latest.status,'finished'); assert.equal(latest.analysisValidation.status,'valid'); assert.equal(latest.g3,undefined)
assert.ok(!parseForgeLedger(run.filePath).some(event=>JSON.stringify(event).includes(message)),'artifact prose must not occur in any ledger line')
const beforeSupplement=creates; await assert.rejects(()=>executeStoryAnalysis({roots,epicRunId:run.runId,storyRunId:story,namespaceId:'n',agentName:'a',expectedSpecHash:hash,supplement:'x'.repeat(4001),runtime}),/SUPPLEMENT_INVALID/); assert.equal(creates,beforeSupplement,'invalid supplement must reject before createCase')
await assert.throws(()=>writeStoryAnalysisArtifact(roots.runStoreRoot,'../evil','exec_ok','prose'),/ARTIFACT_ID_INVALID/); await assert.throws(()=>writeStoryAnalysisArtifact(roots.runStoreRoot,'epic_ok','../evil','prose'),/ARTIFACT_ID_INVALID/)
const dashboard=readFileSync(new URL('../dashboard/forge-routes.mjs',import.meta.url),'utf8'); assert.match(dashboard,/Unsupported Story analysis request field/); assert.match(dashboard,/namespaceId', 'agentName', 'expectedSpecHash', 'supplement/)
console.log('story analysis edge cases: ok')
