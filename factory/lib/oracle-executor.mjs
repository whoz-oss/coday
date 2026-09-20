import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { realpath } from 'node:fs/promises'
import { isAbsolute } from 'node:path'

const LIMIT=16_384
const bounded=(chunks)=>{const value=Buffer.concat(chunks).toString('utf8');return {excerpt:value.slice(0,LIMIT),truncated:value.length>LIMIT}}
export function classifyOracleExecution(definition,result){
 if(result.spawnError||result.timedOut||result.signal)return {classification:'ORACLE_INFRASTRUCTURE',outcome:'indeterminate'}
 if(result.exitCode!==0)return {classification:'PRODUCT_REGRESSION',outcome:'fail'}
 if(definition.success.requireWork&&result.counts.executed===0)return {classification:'EMPTY_SUCCESS',outcome:'indeterminate'}
 return {classification:'CLEAN',outcome:'pass'}
}
export async function validateOracleRoot(repoRoot){if(typeof repoRoot!=='string'||!isAbsolute(repoRoot))throw Object.assign(new Error('INVALID_ORACLE_ROOT'),{code:'INVALID_ORACLE_ROOT'});return realpath(repoRoot)}
export function oracleRootIdentity(repoRoot){return `sha256:${createHash('sha256').update(repoRoot).digest('hex')}`}
function processEnvironment(source=process.env){const env={};for(const key of ['PATH','HOME','TMPDIR','TMP','TEMP','SystemRoot','WINDIR','PATHEXT'])if(typeof source[key]==='string')env[key]=source[key];return env}
export function executeOracle(definition,{repoRoot,countTaskOutcomes,spawnImpl=spawn,environment=process.env}){return new Promise(resolve=>{
 const started=Date.now(),stdout=[],stderr=[];let timedOut=false,spawnError=null,settled=false
 const child=spawnImpl(definition.argv[0],definition.argv.slice(1),{cwd:repoRoot,env:processEnvironment(environment),stdio:['ignore','pipe','pipe'],shell:false,detached:process.platform!=='win32'})
 child.stdout?.on('data',x=>stdout.push(Buffer.from(x)));child.stderr?.on('data',x=>stderr.push(Buffer.from(x)));child.on('error',e=>{spawnError=e.code??'SPAWN_FAILURE'})
 const timer=setTimeout(()=>{timedOut=true;try{process.platform==='win32'?child.kill('SIGKILL'):process.kill(-child.pid,'SIGKILL')}catch{try{child.kill('SIGKILL')}catch{}}},definition.timeoutMs)
 child.on('close',(code,signal)=>{if(settled)return;settled=true;clearTimeout(timer);const out=bounded(stdout),err=bounded(stderr),counts=countTaskOutcomes(`${out.excerpt}\n${err.excerpt}`);const base={exitCode:code,signal,timedOut,durationMs:Date.now()-started,spawnError,counts,stdout:out,stderr:err};resolve({...base,...classifyOracleExecution(definition,base)})})
 })}
export function oracleArtifact(result){const raw=JSON.stringify({stdout:result.stdout.excerpt,stderr:result.stderr.excerpt});return {raw,hash:`sha256:${createHash('sha256').update(raw).digest('hex')}`}}
