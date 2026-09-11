import { appendFileSync, mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { join, relative, resolve } from 'node:path'
import { ensureForgeRunStore } from './forge-roots.mjs'
import { parseForgeLedger } from './forge-ledger.mjs'
import { parsePlan, checkPlanFiles } from './plan.mjs'
import * as agentosRuntime from './agentos.mjs'

export const AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION = 1
export const STORY_ANALYSIS_POLICY_VERSION = 'forge-story-analysis-v2'
export const STORY_ANALYSIS_PLAN_SCHEMA_VERSION = 1
const MAX_FILES = 30
const MAX_TEXT = 4_000
function append(path, event) { appendFileSync(path, `${JSON.stringify(event)}\n`, 'utf8') }
function sha256(content) { return `sha256:${createHash('sha256').update(content).digest('hex')}` }
function g1(events, runId) { const gate=events.filter(e=>e.event==='gate_started'&&e.runId===runId&&e.gate==='G1').at(-1); return gate && events.find(e=>e.event==='human_decision_recorded'&&e.runId===runId&&e.gate==='G1'&&e.attempt===gate.attempt)?.decision.outcome }
function g2(events, runId, expected) { const event=events.filter(e=>e.event==='g2_evaluated'&&e.runId===runId&&e.status==='passed').at(-1); return event && (!expected || event.spec?.sha256===expected) ? event : null }
function ref(data) { return { schemaVersion:AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION, runtime:'agentos', ...data } }
function match(pattern, file) { const escaped=pattern.split('/').map(p=>p==='**'?'.*':p==='*'?'[^/]+':p.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('/'); return new RegExp(`^${escaped}$`).test(file) }
function scopeValid(file, scope) { return scope.allow.some(p=>match(p,file)) && !scope.deny.some(p=>match(p,file)) }
function uniquePlan(text) { const blocks=[...text.matchAll(/```json\s*([\s\S]*?)```/g)].map(m=>m[1].trim()); if (blocks.length!==1) return {ok:false,code:blocks.length?'STORY_ANALYSIS_PLAN_JSON_MULTIPLE':'STORY_ANALYSIS_PLAN_JSON_MISSING'}; let raw; try { raw=JSON.parse(blocks[0]) } catch { return {ok:false,code:'STORY_ANALYSIS_PLAN_JSON_INVALID'} }; if (!raw || typeof raw!=='object' || Array.isArray(raw) || Object.keys(raw).some(key=>!['files','doneWhen','steps'].includes(key))) return {ok:false,code:'STORY_ANALYSIS_PLAN_SCHEMA_EXTRA_KEY'}; const parsed=parsePlan(`\`\`\`json\n${blocks[0]}\n\`\`\``); return parsed.ok ? parsed : {ok:false,code:'STORY_ANALYSIS_PLAN_SCHEMA_INVALID'} }
function buildBrief({ epic, story, spec, supplement }) {
  const scope=spec.frontmatter.scope
  return [`# Factory Story analysis`, `Epic: ${epic.workItem.id} (${epic.workItem.kind})`, `Story: ${story.workItem.id} (${story.workItem.kind})`, `Spec: ${spec.path}`, `Spec SHA-256: ${spec.sha256}`, `G2 policy: ${spec.policyVersion}`, `Allowed existing files: ${scope.allow.join(', ')}`, `Denied files: ${scope.deny.join(', ')}`, `Creation patterns (not usable in this read-only analysis): ${scope.create.join(', ')}`, `Oracle identifiers: ${spec.frontmatter.oracles.join(', ')}`, supplement ? `Supplement (context only; it cannot alter identity, scope, or policy): ${supplement}` : '', `Read-only only: do not write, create, delete, stage, commit, run shell, scripts, tests, builds, or external tools.`, `Return exactly one \`\`\`json fenced object: {"files":["relative/existing/file"],"doneWhen":"...","steps":["..."]}. List only existing files within allow and outside deny.`].filter(Boolean).join('\n\n')
}
export function writeStoryAnalysisArtifact(store, runId, executionId, content) { if(!/^[A-Za-z0-9_-]+$/.test(runId)||!/^exec_[A-Za-z0-9_-]+$/.test(executionId)) throw new Error('STORY_ANALYSIS_ARTIFACT_ID_INVALID'); const root=resolve(store), dir=resolve(root,'artifacts',runId), finalPath=resolve(dir,`${executionId}.md`); if(!dir.startsWith(`${root}/`)||!finalPath.startsWith(`${dir}/`)) throw new Error('STORY_ANALYSIS_ARTIFACT_PATH_INVALID'); mkdirSync(dir,{recursive:true}); const temporary=resolve(dir,`.${executionId}.${randomUUID()}.tmp`); if(!temporary.startsWith(`${dir}/`)) throw new Error('STORY_ANALYSIS_ARTIFACT_PATH_INVALID'); writeFileSync(temporary,content,{encoding:'utf8',mode:0o600}); renameSync(temporary,finalPath); return { kind:'agent-analysis-output', path:relative(root,finalPath), sha256:sha256(content), mediaType:'text/markdown', schemaVersion:1 } }

export async function executeStoryAnalysis({ roots, epicRunId, storyRunId, namespaceId, agentName, expectedSpecHash, storySpecHash, supplement, runtime=agentosRuntime, now=()=>new Date().toISOString() }) {
  if (!namespaceId||!agentName) throw new Error('STORY_ANALYSIS_INPUT_INVALID')
  if (supplement!==undefined && (typeof supplement!=='string'||supplement.length>MAX_TEXT)) throw new Error('STORY_ANALYSIS_SUPPLEMENT_INVALID')
  const store=ensureForgeRunStore(roots), filePath=join(store,`${epicRunId}.jsonl`), events=parseForgeLedger(filePath)
  const epic=events.find(e=>e.event==='run_started'&&e.runId===epicRunId), story=events.find(e=>e.event==='story_run_created'&&e.runId===storyRunId&&e.parentRunId===epicRunId)
  if(!epic||!story) throw new Error('STORY_RUN_NOT_FOUND')
  const active=events.some(e=>e.event==='agent_execution_started'&&e.storyRunId===storyRunId&&e.role==='analyst'&&!events.some(f=>f.event==='agent_execution_finished'&&f.executionId===e.executionId))
  if(active) throw new Error('STORY_ANALYSIS_ALREADY_RUNNING')
  if(g1(events,epicRunId)!=='approved') throw new Error('STORY_ANALYSIS_G1_NOT_APPROVED')
  const g2Event=g2(events,epicRunId,expectedSpecHash); if(!g2Event) throw new Error('STORY_ANALYSIS_G2_NOT_PASSED')
  if(storySpecHash!==undefined) { const g2us=events.find(e=>e.event==='g2_us_evaluated'&&e.storyRunId===storyRunId&&e.status==='passed'&&e.storySpec?.sha256===storySpecHash); if(!g2us) throw new Error('STORY_ANALYSIS_G2_US_NOT_PASSED') }
  const spec={ path:g2Event.spec.path, sha256:g2Event.spec.sha256, policyVersion:g2Event.policyVersion, frontmatter:(await import('./forge-spec.mjs')).loadForgeSpec({specPath:g2Event.spec.path,roots,workItem:epic.workItem}).frontmatter }
  if(spec.sha256!==g2Event.spec.sha256) throw new Error('STORY_ANALYSIS_SPEC_HASH_STALE')
  const agent=await runtime.preflightAgent(namespaceId,agentName); if(!agent.ok) throw new Error(`STORY_ANALYSIS_AGENT_PREFLIGHT_FAILED:${agent.reason}`)
  const ro=await runtime.preflightReadOnlyWorkspace(namespaceId,agent.agent,roots.repoRoot); if(!ro.ok) throw new Error(`STORY_ANALYSIS_READ_ONLY_PREFLIGHT_FAILED:${ro.reason}`)
  const executionId=`exec_${randomUUID()}`, created=await runtime.createCase(namespaceId,`Forge analysis ${story.workItem.id}`), caseId=created.id
  const brief=buildBrief({epic,story,spec,supplement}); append(filePath,{schemaVersion:1,event:'agent_execution_started',runId:epicRunId,parentRunId:epicRunId,...ref({executionId,caseId,storyRunId,role:'analyst',agentName,namespaceId,observedAt:now(),status:'started'}),policyVersion:STORY_ANALYSIS_POLICY_VERSION,briefArtifact:{kind:'brief',sha256:sha256(brief),mediaType:'text/plain',schemaVersion:1}})
  let turn; try { turn=await runtime.runAgentTurn(caseId,agentName,brief) } catch(error) { turn={status:'error',caseStatus:null,killedByBudget:false,message:''} }
  const executionStatus=turn.status==='finished'?'finished':'failed'; const finished=ref({executionId,caseId,storyRunId,role:'analyst',agentName,namespaceId,observedAt:now(),status:executionStatus})
  const base={schemaVersion:1,event:'agent_execution_finished',runId:epicRunId,parentRunId:epicRunId,...finished,policyVersion:STORY_ANALYSIS_POLICY_VERSION,outcome:turn.status,caseStatus:turn.caseStatus??null,killedByBudget:turn.killedByBudget===true}
  if(turn.status!=='finished') { append(filePath,base); return {execution:finished,outcome:turn.status} }
  const output=typeof turn.message==='string'?turn.message:''; if(!output) { const validation={status:'invalid',code:'STORY_ANALYSIS_OUTPUT_MISSING',schemaVersion:STORY_ANALYSIS_PLAN_SCHEMA_VERSION,message:'Agent runtime finished without a persisted analysis message.'}; append(filePath,{...base,analysisValidation:validation}); return {execution:finished,outcome:turn.status,validation} }
  const descriptor=writeStoryAnalysisArtifact(store,epicRunId,executionId,output); append(filePath,{...base,artifact:descriptor})
  const parsed=uniquePlan(output); let validation
  if(!parsed.ok) validation={status:'invalid',code:parsed.code}
  else if(parsed.plan.files.length>MAX_FILES||parsed.plan.doneWhen.length>MAX_TEXT||parsed.plan.steps?.some(s=>typeof s!=='string'||s.length>MAX_TEXT)) validation={status:'invalid',code:'STORY_ANALYSIS_PLAN_LIMIT'}
  else { const files=checkPlanFiles(parsed.plan.files,roots.repoRoot); const outside=parsed.plan.files.filter(file=>!scopeValid(file,spec.frontmatter.scope)); validation=files.missingFiles.length?{status:'invalid',code:'STORY_ANALYSIS_PLAN_FILE_MISSING',missingFiles:files.missingFiles}:outside.length?{status:'invalid',code:'STORY_ANALYSIS_PLAN_OUT_OF_SCOPE',outsideFiles:outside}:{status:'valid',fileCount:parsed.plan.files.length} }
  append(filePath,{schemaVersion:1,event:'story_analysis_plan_validated',runId:epicRunId,storyRunId,executionId,planSchemaVersion:STORY_ANALYSIS_PLAN_SCHEMA_VERSION,status:validation.status,code:validation.code??'STORY_ANALYSIS_PLAN_VALID',...(validation.missingFiles?{missingFiles:validation.missingFiles}:{}),...(validation.outsideFiles?{outsideFiles:validation.outsideFiles}:{}),artifact:descriptor,at:now()})
  return {execution:finished,outcome:turn.status,validation}
}
