import { mkdir, mkdtemp, realpath, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { GIT_WORKTREE_ERROR_CODES, GitWorktreeProvisioner } from '../lib/git-worktree.mjs'

let passed=0,failed=0
function expect(name,actual,expected){const ok=JSON.stringify(actual)===JSON.stringify(expected);console.log(`${ok?'✓':'✗'} ${name}`);if(!ok)console.log(`  expected=${JSON.stringify(expected)} actual=${JSON.stringify(actual)}`);ok?passed++:failed++}
async function rejects(name,fn,code){try{await fn();expect(name,'no error',code)}catch(error){expect(name,error.code,code)}}

const root=await mkdtemp(join(tmpdir(),'factory-git-'))
try{
 const repo=join(root,'repo'),sub=join(repo,'sub'),worktrees=join(root,'worktrees'),outside=join(root,'outside')
 await mkdir(sub,{recursive:true});await mkdir(worktrees);await mkdir(outside)
 const canonicalRepo=await realpath(repo),canonicalWorktrees=await realpath(worktrees)
 const sha40='a'.repeat(40),sha64='b'.repeat(64),calls=[]
 let status='',base=sha40,branchExists=false,addFails=false,removeFails=false,listed=''
 const runner=async(file,args,options={})=>{calls.push({file,args:[...args],cwd:options.cwd});const command=args.join(' ')
  if(command==='rev-parse --show-toplevel')return{exitCode:0,stdout:`${canonicalRepo}\n`}
  if(command==='status --porcelain')return{exitCode:0,stdout:status}
  if(command.startsWith('rev-parse --verify '))return{exitCode:0,stdout:`${base}\n`}
  if(command.startsWith('show-ref --verify --hash '))return branchExists?{exitCode:0,stdout:`${base}\n`}:{exitCode:1,stdout:''}
  if(command==='worktree list --porcelain')return{exitCode:0,stdout:listed}
  if(args[0]==='worktree'&&args[1]==='add'){if(addFails)return{exitCode:1,stdout:''};await mkdir(args[4]);branchExists=true;listed=`worktree ${args[4]}\nHEAD ${base}\nbranch refs/heads/${args[3]}\n`;return{exitCode:0,stdout:''}}
  if(args[0]==='worktree'&&args[1]==='remove')return{exitCode:removeFails?1:0,stdout:''}
  if(command==='worktree prune')return{exitCode:0,stdout:''}
  return{exitCode:1,stdout:''}}
 const git=new GitWorktreeProvisioner({runner,worktreesRoot:worktrees})
 const inspected=await git.inspectRepository(repo,'main')
 expect('inspect captures 40 SHA',inspected.baseCommit,sha40)
 expect('runner executable/args/cwd',calls.slice(0,3),[
  {file:'git',args:['rev-parse','--show-toplevel'],cwd:canonicalRepo},
  {file:'git',args:['status','--porcelain'],cwd:canonicalRepo},
  {file:'git',args:['rev-parse','--verify','main^{commit}'],cwd:canonicalRepo},
 ])
 base=sha64;expect('inspect accepts 64 SHA',(await git.inspectRepository(repo,'main')).baseCommit,sha64)
 await rejects('subdirectory rejected',()=>git.inspectRepository(sub,'main'),GIT_WORKTREE_ERROR_CODES.NOT_REPOSITORY_ROOT)
 status=' M file';await rejects('dirty repository rejected',()=>git.inspectRepository(repo,'main'),GIT_WORKTREE_ERROR_CODES.DIRTY_REPOSITORY);status='';base=sha40
 const target=join(canonicalWorktrees,'unit-1');calls.length=0
 const provisioned=await git.provisionWorktree({repoRoot:repo,integrationBranch:'main',branch:'feature/unit-1',worktreePath:target})
 expect('provision returns exact destination',provisioned.worktreePath,target)
 expect('exact provision argument array',calls.find(c=>c.args[0]==='worktree'&&c.args[1]==='add'),{file:'git',args:['worktree','add','-b','feature/unit-1',target,sha40],cwd:canonicalRepo})
 expect('reconcile owned',(await git.reconcile({repoRoot:repo,branch:'feature/unit-1',worktreePath:target,baseCommit:sha40})).status,'owned')
 const advanced='c'.repeat(40);base=advanced;listed=`worktree ${target}\nHEAD ${advanced}\nbranch refs/heads/feature/unit-1\n`;const advancedOwned=await git.reconcile({repoRoot:repo,branch:'feature/unit-1',worktreePath:target,baseCommit:sha40});expect('advanced HEAD remains owned with immutable base',[advancedOwned.status,advancedOwned.baseCommit,advancedOwned.headCommit],['owned',sha40,advanced])
 base=sha40;listed=`worktree ${target}\nHEAD ${advanced}\nbranch refs/heads/feature/unit-1\n`;expect('branch ref and registered HEAD mismatch uncertain',(await git.reconcile({repoRoot:repo,branch:'feature/unit-1',worktreePath:target,baseCommit:sha40})).status,'uncertain')
 base=advanced;listed=`worktree ${target}\nHEAD ${advanced}\nbranch refs/heads/feature/unit-1\n`;expect('advanced owned worktree removable',(await git.removeWorktree({repoRoot:repo,branch:'feature/unit-1',worktreePath:target,baseCommit:sha40})).removed,true)
 base=sha40;listed=`worktree ${target}\nHEAD ${sha40}\nbranch refs/heads/feature/unit-1\n`
 const absent=join(canonicalWorktrees,'absent');branchExists=false;listed='';expect('realistic empty metadata is absent',(await git.reconcile({repoRoot:repo,branch:'feature/absent',worktreePath:absent,baseCommit:sha40})).status,'absent')
 listed='\n\n';expect('trailing blank records remain absent',(await git.reconcile({repoRoot:repo,branch:'feature/absent',worktreePath:absent,baseCommit:sha40})).status,'absent')
 listed='HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nbranch refs/heads/feature/absent\n\n';calls.length=0;expect('record missing worktree is uncertain',(await git.reconcile({repoRoot:repo,branch:'feature/absent',worktreePath:absent,baseCommit:sha40})).status,'uncertain');expect('malformed reconciliation invokes no add/remove',calls.some(c=>c.args[0]==='worktree'&&['add','remove'].includes(c.args[1])),false)
 listed='';branchExists=true;expect('branch conflict is uncertain',(await git.reconcile({repoRoot:repo,branch:'feature/absent',worktreePath:absent,baseCommit:sha40})).status,'uncertain')
 listed=`worktree ${target}\nHEAD ${sha40}\nbranch refs/heads/other\n`;expect('registered branch mismatch uncertain',(await git.reconcile({repoRoot:repo,branch:'feature/unit-1',worktreePath:target,baseCommit:sha40})).status,'uncertain')
 listed=`worktree ${target}\nHEAD ${sha40}\nbranch refs/heads/feature/unit-1\n`;branchExists=true;base=sha40
 removeFails=false;calls.length=0;expect('safe registered removal',(await git.removeWorktree({repoRoot:repo,branch:'feature/unit-1',worktreePath:target,baseCommit:sha40})).removed,true)
 expect('remove uses exact args',calls.find(c=>c.args[0]==='worktree'&&c.args[1]==='remove').args,['worktree','remove',target])
 await rejects('repo root removal refused',()=>git.removeWorktree({repoRoot:repo,branch:'main',worktreePath:repo,baseCommit:sha40}),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 await rejects('ancestor removal refused',()=>git.removeWorktree({repoRoot:repo,branch:'main',worktreePath:root,baseCommit:sha40}),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 await rejects('outside removal refused',()=>git.removeWorktree({repoRoot:repo,branch:'x',worktreePath:outside,baseCommit:sha40}),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 branchExists=false;listed='';await mkdir(join(worktrees,'arbitrary'));expect('unregistered existing path uncertain',(await git.reconcile({repoRoot:repo,branch:'x',worktreePath:join(worktrees,'arbitrary'),baseCommit:sha40})).status,'uncertain')
 await rejects('relative destination rejected',()=>git.canonicalDestination('relative'),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 await rejects('noncanonical destination rejected',()=>git.canonicalDestination(`${worktrees}/x/../y`),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 await rejects('worktrees root rejected',()=>git.canonicalDestination(worktrees),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 await rejects('outside destination rejected',()=>git.canonicalDestination(outside),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 const link=join(worktrees,'escape');await symlink(outside,link);await rejects('symlink parent escape rejected',()=>git.canonicalDestination(join(link,'child')),GIT_WORKTREE_ERROR_CODES.INVALID_PATH)
 const conflict=join(worktrees,'conflict');await mkdir(conflict);branchExists=false;listed='';await rejects('existing path provision conflict',()=>git.provisionWorktree({repoRoot:repo,integrationBranch:'main',branch:'feature/conflict',worktreePath:conflict}),GIT_WORKTREE_ERROR_CODES.OWNERSHIP_UNCERTAIN)
}finally{await rm(root,{recursive:true,force:true})}
console.log(`\nResult: ${passed} passed, ${failed} failed`);process.exit(failed?1:0)
