import { WorkUnitEnvironmentService } from '../lib/work-unit-environment-service.mjs'

let passed=0,failed=0
function expect(name,actual,expected){const ok=JSON.stringify(actual)===JSON.stringify(expected);console.log(`${ok?'✓':'✗'} ${name}`);if(!ok)console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);ok?passed++:failed++}
const ns='123e4567-e89b-42d3-a456-426614174000',caseId='223e4567-e89b-42d3-a456-426614174000',sha='a'.repeat(40)
const input={environmentId:'env-1',workUnitId:'unit-1',namespaceId:ns,repoRoot:'/repo',integrationBranch:'main',branch:'feature/unit-1',worktreePath:'/worktrees/unit-1',createdBy:'factory'}
function harness({fault=async()=>{},addFailure=false,uncertain=false,removeFailure=false}={}){let snapshot=null,revision=0,owned=false,adds=0
 const store={async read(_ns,id){return snapshot?.environment.environmentId===id?snapshot:null},async reserve(environment){snapshot={revision:++revision,environment};return{ok:true,changed:true,snapshot}},async transition(_ns,_id,environment){snapshot={revision:++revision,environment};return{ok:true,changed:true,snapshot}},async list(){return snapshot?[snapshot]:[]}}
 const git={async reconcile(environment){if(uncertain)return{status:'uncertain'};return owned?{status:'owned',...environment,baseCommit:sha,headCommit:sha}:{status:'absent'}},async provisionWorktree(value,onReady){await onReady({...value,baseCommit:sha});adds++;if(addFailure)throw Object.assign(new Error('failed'),{code:'GIT_FAILED'});owned=true;return{...value,baseCommit:sha,headCommit:sha}},async removeWorktree(){if(removeFailure)throw Object.assign(new Error('failed'),{code:'GIT_FAILED'});owned=false;return{removed:true}}}
 return{store,git,service:new WorkUnitEnvironmentService({store,git,fault,clock:()=>new Date('2025-01-01T00:00:00.000Z')}),get snapshot(){return snapshot},get adds(){return adds},set owned(v){owned=v}}}
{
 const h=harness();const first=await h.service.provision(input),second=await h.service.provision(input)
 expect('successful repeated provision is idempotent',[first.ok,second.ok,second.changed,h.adds],[true,true,false,1])
 h.git.reconcile=async environment=>({status:'owned',...environment,baseCommit:environment.baseCommit,headCommit:'b'.repeat(40)});const advanced=await h.service.provision(input);expect('advanced HEAD preserves provisioning baseline',[advanced.ok,advanced.snapshot.environment.baseCommit,advanced.headCommit,h.adds],[true,sha,'b'.repeat(40),1])
 const bound=await h.service.bindParentCase(ns,'env-1',caseId),again=await h.service.bindParentCase(ns,'env-1',caseId)
 expect('bind exact idempotency',[bound.ok,again.changed,h.snapshot.environment.lifecycleState,h.snapshot.environment.parentCaseId],[true,false,'active',caseId])
}
{
 let crash=true;const h=harness({fault:async seam=>{if(seam==='after-git-add'&&crash){crash=false;throw Object.assign(new Error('crash'),{code:'INJECTED_CRASH'})}}})
 const interrupted=await h.service.provision(input);expect('post-add fault leaves durable baseline',[interrupted.error.code,h.snapshot.environment.baseCommit,h.snapshot.environment.lifecycleState,h.adds],['POST_ADD_RECOVERY_REQUIRED',sha,'provisioning',1])
 const retried=await h.service.provision(input);expect('retry finalizes without second add',[retried.ok,retried.snapshot.environment.baseCommit,h.adds],[true,sha,1])
}
{
 const h=harness();let release;const gate=new Promise(r=>release=r),original=h.git.provisionWorktree.bind(h.git);h.git.provisionWorktree=async(...args)=>{await gate;return original(...args)}
 const one=h.service.provision(input),two=h.service.provision(input);release();const results=await Promise.all([one,two])
 expect('concurrent provisions issue one add',[results.every(r=>r.ok),h.adds],[true,1])
}
{
 const h=harness({uncertain:true});await h.store.reserve({schemaVersion:'1',...input,baseCommit:null,createdAt:'2025-01-01T00:00:00.000Z',lifecycleState:'provisioning'});const result=await h.service.provision(input)
 expect('uncertain ownership remains provisioning',[result.error.code,h.snapshot.environment.lifecycleState],['OWNERSHIP_UNCERTAIN','provisioning'])
}
{
 const h=harness({addFailure:true});let code;try{await h.service.provision(input)}catch(error){code=error.code}
 expect('conclusive creation failure marks error',[code,h.snapshot.environment.lifecycleState],['GIT_FAILED','error'])
}
{
 const h=harness({removeFailure:true});await h.service.provision(input);await h.store.transition(ns,'env-1',{...h.snapshot.environment,lifecycleState:'error'});let code;try{await h.service.remove(ns,'env-1')}catch(error){code=error.code}
 expect('removal failure does not mark removed',[code,h.snapshot.environment.lifecycleState],['GIT_FAILED','error'])
}
console.log(`\nResult: ${passed} passed, ${failed} failed`);process.exit(failed?1:0)
