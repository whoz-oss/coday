import { createHash } from 'node:crypto'
import { readFile, readdir } from 'node:fs/promises'
import { basename, join } from 'node:path'

const SAFE=/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const VERSION=/^\d+\.\d+\.\d+$/
const FIELDS=new Set(['schemaVersion','id','version','domain','argv','cwd','timeoutMs','success','applicable'])
const SUCCESS_FIELDS=new Set(['rule','requireWork'])
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value
export function validateOracleDefinition(value){
 if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(k=>!FIELDS.has(k)))throw new Error('INVALID_ORACLE_DEFINITION')
 if(value.schemaVersion!=='1'||!SAFE.test(value.id??'')||!VERSION.test(value.version??'')||!SAFE.test(value.domain??''))throw new Error('INVALID_ORACLE_DEFINITION')
 if(!Array.isArray(value.argv)||value.argv.length===0||value.argv.length>32||value.argv.some(x=>typeof x!=='string'||!x||x.length>512||/[\r\n\0]/.test(x)))throw new Error('INVALID_ORACLE_DEFINITION')
 // argv is executed directly without a shell. Reject shell interpreters and
 // shell-evaluation flags anyway: deterministic oracles must identify a fixed
 // executable contract, not embed a second command language in their definition.
 const executable=value.argv[0].replace(/\\/g,'/').split('/').at(-1)?.toLowerCase()
 if(['sh','bash','zsh','dash','ksh','cmd','cmd.exe','powershell','powershell.exe','pwsh','pwsh.exe'].includes(executable)||value.argv.some((arg,index)=>index>0&&['-c','--command','/c','-command','-encodedcommand'].includes(arg.toLowerCase())))throw new Error('INVALID_ORACLE_DEFINITION')
 if(value.cwd!=='repo-root'||!Number.isSafeInteger(value.timeoutMs)||value.timeoutMs<1||value.timeoutMs>3_600_000)throw new Error('INVALID_ORACLE_DEFINITION')
 if(!value.success||typeof value.success!=='object'||Array.isArray(value.success)||Object.keys(value.success).some(k=>!SUCCESS_FIELDS.has(k))||value.success.rule!=='exit-code'||typeof value.success.requireWork!=='boolean')throw new Error('INVALID_ORACLE_DEFINITION')
 if(!value.applicable||typeof value.applicable!=='object'||Array.isArray(value.applicable)||Object.keys(value.applicable).some(k=>!['workflowTypes','stepIds'].includes(k))||!Array.isArray(value.applicable.workflowTypes)||!value.applicable.workflowTypes.length||value.applicable.workflowTypes.some(x=>!SAFE.test(x))||!Array.isArray(value.applicable.stepIds)||!value.applicable.stepIds.length||value.applicable.stepIds.some(x=>!SAFE.test(x)))throw new Error('INVALID_ORACLE_DEFINITION')
 return Object.freeze({...value,argv:Object.freeze([...value.argv]),success:Object.freeze({...value.success}),applicable:Object.freeze({workflowTypes:Object.freeze([...value.applicable.workflowTypes]),stepIds:Object.freeze([...value.applicable.stepIds])})})
}
export const hashOracleDefinition=d=>`sha256:${createHash('sha256').update(JSON.stringify(canonical(d))).digest('hex')}`
export class OracleDefinitionRegistry{
 constructor(root){this.root=root;this.items=new Map()}
 async initialize(){const files=(await readdir(this.root)).filter(x=>x.endsWith('.json')).sort();const next=new Map();for(const file of files){const d=validateOracleDefinition(JSON.parse(await readFile(join(this.root,file),'utf8')));if(basename(file,'.json')!==`${d.id}@${d.version}`)throw new Error('ORACLE_PATH_IDENTITY_MISMATCH');if(next.has(d.id))throw new Error('DUPLICATE_ORACLE_ID');next.set(d.id,d)}this.items=next;return this}
 get(id){return this.items.get(id)??null}
}
