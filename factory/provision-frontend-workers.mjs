import { realpathSync } from 'node:fs'
import { basename, dirname, resolve } from 'node:path'

const BASE_URL=process.env.AGENTOS_URL??'http://localhost:8124'
const USER=process.env.FACTORY_USER??'benjamin.valdes'
const namespaceId=process.env.FACTORY_NAMESPACE_ID
const requestedRoot=process.env.FACTORY_ROOT
const modelName=process.env.FACTORY_MODEL_NAME
if(!namespaceId||!requestedRoot||!modelName)throw new Error('Required: FACTORY_NAMESPACE_ID, FACTORY_ROOT, FACTORY_MODEL_NAME')
const repoRoot=realpathSync(resolve(requestedRoot))
const expectedWorktreesRoot=process.env.FACTORY_WORKTREES_ROOT
if(expectedWorktreesRoot){
 const worktreesRoot=realpathSync(resolve(expectedWorktreesRoot))
 if(realpathSync(dirname(repoRoot))!==worktreesRoot)throw new Error(`FACTORY_ROOT must be a direct child of FACTORY_WORKTREES_ROOT: ${worktreesRoot}`)
}
if(!/^factory-demo-frontend-rehearsal-[A-Za-z0-9._-]+-demo$/.test(basename(repoRoot)))throw new Error('FACTORY_ROOT must resolve to a Factory frontend rehearsal worktree')

const roles=[
 ['ForgeProductWorker',true,'Analyse tickets and produce bounded product specifications.'],
 ['ForgeUXDesigner',true,'Produce bounded UX contracts from approved product specifications.'],
 ['Searcher',true,'Inspect the frontend codebase and report relevant source locations.'],
 ['ForgeTechDesigner',true,'Produce bounded frontend technical designs.'],
 ['ForgeFrontendWorker',false,'Implement only the approved frontend design inside the explicit Sprint scope.'],
 ['ForgeReviewer',true,'Review the implementation and report findings without modifying files.'],
]
const integrationName=(readOnly)=>readOnly?'FACTORY_FRONTEND_FILES_RO':'FACTORY_FRONTEND_FILES_RW'
async function request(method,path,body){const response=await fetch(`${BASE_URL}${path}`,{method,headers:{'content-type':'application/json','x-external-user-id':USER},...(body===undefined?{}:{body:JSON.stringify(body)})});const text=await response.text();if(!response.ok)throw new Error(`AgentOS ${method} ${path} -> HTTP ${response.status}: ${text.slice(0,500)}`);return text?JSON.parse(text):null}
async function upsertIntegration(existing,name,readOnly){const payload={namespaceId,userId:null,name,integrationType:'FILE_ACCESS',description:`Factory frontend ${readOnly?'read-only':'writable'} access bounded to rehearsal worktree.`,parameters:{rootPath:repoRoot,readOnly,readMaxSizeMb:10,extraDenyPatterns:[]}};const found=existing.find((item)=>item.name===name);return request(found?'PUT':'POST',found?`/api/integration-configs/${found.id}`:'/api/integration-configs',payload)}
async function upsertAgent(existing,name,readOnly,description){const files=integrationName(readOnly);const payload={namespaceId,name,description,instructions:`You are ${name}, a non-interactive Factory frontend phase worker. Stay inside the supplied brief and repository access. ${readOnly?'Never modify files. Submit artifact Markdown as bounded raw content through FACTORY__submit_step_result; never choose artifact paths or hashes. Technical review submits structured findings.':'Modify only explicitly allowed Sprint frontend paths. Submit exact modifiedFiles claims through FACTORY__submit_step_result. Never build, test, lint, commit, push, stage, stash, delegate, or ask the user.'} You MUST call FACTORY__submit_step_result exactly once before finishing. Your assistant message is narrative only and never authoritative.`,modelName,skillSelectors:[],integrations:{[files]:null,FACTORY:['submit_step_result'],QUERY_USER:[]},advancedExecution:false,subAgents:[],enabled:true};const found=existing.find((item)=>item.name===name);const saved=await request(found?'PUT':'POST',found?`/api/agent-configs/${found.id}`:'/api/agent-configs',payload);return request('GET',`/api/agent-configs/${saved.id}`)}
const integrations=await request('GET',`/api/integration-configs?namespaceId=${namespaceId}`)
await upsertIntegration(integrations,integrationName(true),true)
await upsertIntegration(integrations,integrationName(false),false)
const agents=await request('GET',`/api/agent-configs/by-parentId/${namespaceId}`)
for(const [name,readOnly,description] of roles){const agent=await upsertAgent(agents,name,readOnly,description);const keys=Object.keys(agent.integrations??{}).sort();const expected=[integrationName(readOnly),'FACTORY','QUERY_USER'].sort();const problems=[];if(agent.enabled!==true)problems.push('disabled');if((agent.subAgents??[]).length)problems.push('subAgents');if(!Array.isArray(agent.integrations?.QUERY_USER)||agent.integrations.QUERY_USER.length)problems.push('QUERY_USER');if(JSON.stringify(keys)!==JSON.stringify(expected))problems.push(`integrations=${keys.join(',')}`);if(agent.modelName!==modelName)problems.push(`model=${agent.modelName??'(missing)'}`);if(problems.length)throw new Error(`${name} failed verification: ${problems.join('; ')}`);console.log(`Ready: ${name} (${readOnly?'read-only':'writable'})`)}
const effectiveIntegrations=await request('GET',`/api/integration-configs?namespaceId=${namespaceId}`)
for(const readOnly of [true,false]){const item=effectiveIntegrations.find((entry)=>entry.name===integrationName(readOnly));if(item?.integrationType!=='FILE_ACCESS'||item.parameters?.rootPath!==repoRoot||item.parameters?.readOnly!==readOnly)throw new Error(`${integrationName(readOnly)} failed verification`)}
console.log(`Provisioned 6 exact workers in ${namespaceId}, bounded to ${repoRoot}.`)
