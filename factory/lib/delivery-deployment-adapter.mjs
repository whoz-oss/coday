const SAFE=/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
export const DELIVERY_ADAPTER_OUTCOMES=Object.freeze(['running','succeeded','failed','indeterminate'])
export function normalizeDeliveryAdapterOutcome(value){if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(k=>!['state','correlationRef','resultRef','errorCode','observedAt'].includes(k))||!DELIVERY_ADAPTER_OUTCOMES.includes(value.state))return{ok:false,error:{code:'INVALID_DELIVERY_ADAPTER_OUTCOME'}};for(const key of ['correlationRef','resultRef','errorCode'])if(value[key]!==undefined&&!SAFE.test(value[key]))return{ok:false,error:{code:'INVALID_DELIVERY_ADAPTER_OUTCOME',path:key}};if(value.observedAt!==undefined&&(!Number.isFinite(Date.parse(value.observedAt))||new Date(Date.parse(value.observedAt)).toISOString()!==value.observedAt))return{ok:false,error:{code:'INVALID_DELIVERY_ADAPTER_OUTCOME',path:'observedAt'}};return{ok:true,value:Object.freeze({...value})}}
/** Providers must honor the same operationId idempotently. Call inspect before replaying running or indeterminate operations. */
export class DeliveryDeploymentAdapter {async deploy(){throw new Error('Not implemented')}async rollback(){throw new Error('Not implemented')}async inspect(){throw new Error('Not implemented')}async reconcile(operation){return this.inspect(operation)}}
export class DeliveryVerificationAdapter {async verify(){throw new Error('Not implemented')}async inspect(){throw new Error('Not implemented')}}
const blocked=async()=>({ok:false,error:{code:'DELIVERY_ADAPTER_NOT_CONFIGURED'}})
export class UnconfiguredDeliveryDeploymentAdapter {deploy=blocked;rollback=blocked;inspect=blocked;reconcile=blocked}
export class UnconfiguredDeliveryVerificationAdapter {verify=blocked;inspect=blocked}
