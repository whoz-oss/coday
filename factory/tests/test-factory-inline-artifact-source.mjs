import assert from 'node:assert/strict'
import {mkdtemp,readFile,rm} from 'node:fs/promises'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {materializeInlineArtifact} from '../lib/factory-agent-step-executor.mjs'
const root=await mkdtemp(join(tmpdir(),'factory-inline-'));try{const markdown='# Analysis\n```json\n{"nested":true}\n```',base={repoRoot:root,workflowId:'wf-1',stepId:'ticket-analysis',attemptId:'attempt-1',expectedKind:'ticket-analysis'},result={artifacts:[{kind:'ticket-analysis',encoding:'markdown',content:markdown}]};const nominal=await materializeInlineArtifact({...base,result});assert.equal(nominal.ok,true);assert.equal(await readFile(join(root,nominal.artifacts[0].path),'utf8'),markdown);assert.equal((await materializeInlineArtifact({...base,stepId:'encoding',result:{artifacts:[{kind:'ticket-analysis',encoding:'base64',content:'eA=='}]}})).code,'ARTIFACT_ENCODING_INVALID');assert.equal((await materializeInlineArtifact({...base,stepId:'large',maxBytes:2,result:{artifacts:[{kind:'ticket-analysis',encoding:'markdown',content:'abc'}]}})).code,'ARTIFACT_CONTENT_TOO_LARGE')}finally{await rm(root,{recursive:true,force:true})}
