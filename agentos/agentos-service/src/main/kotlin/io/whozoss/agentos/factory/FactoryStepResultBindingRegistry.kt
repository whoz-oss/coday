package io.whozoss.agentos.factory

import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class FactoryStepResultBinding(
 val caseId:UUID,
 val namespaceId:UUID,
 val agentName:String,
 val attemptId:String,
 val runtimeId:String,
 val capabilityToken:String,
 val expiresAt:Instant,
 val leased:AtomicBoolean=AtomicBoolean(false),
)

/** Volatile by design: restart loses every capability and therefore fails closed. */
@Service
class FactoryStepResultBindingRegistry(private val clock:Clock=Clock.systemUTC()){
 companion object : KLogging()
 private val bindings=ConcurrentHashMap<UUID,FactoryStepResultBinding>()
 fun bind(binding:FactoryStepResultBinding){require(binding.expiresAt.isAfter(clock.instant()));require(binding.capabilityToken.length in 32..256);check(bindings.putIfAbsent(binding.caseId,binding)==null){"FACTORY_BINDING_ALREADY_EXISTS"}}
 fun context(caseId:UUID,namespaceId:UUID,agentName:String):Map<String,Any?>{
  val binding=validated(caseId,namespaceId,agentName,"lookup")?:return emptyMap()
  if(binding.leased.get()){logger.warn{"Factory result binding lookup: leased caseId=$caseId attemptId=${binding.attemptId}"};return emptyMap()}
  return mapOf("capabilityToken" to binding.capabilityToken,"attemptId" to binding.attemptId,"runtimeId" to binding.runtimeId)
 }
 fun acquire(caseId:UUID,namespaceId:UUID,agentName:String):FactoryStepResultBinding?{
  val binding=validated(caseId,namespaceId,agentName,"acquire")?:return null
  if(!binding.leased.compareAndSet(false,true)){logger.warn{"Factory result binding acquire: already leased caseId=$caseId attemptId=${binding.attemptId}"};return null}
  logger.info{"Factory result binding acquire: accepted caseId=$caseId attemptId=${binding.attemptId} agent=$agentName"}
  return binding
 }
 fun acknowledge(binding:FactoryStepResultBinding){
  if(bindings.remove(binding.caseId,binding))logger.info{"Factory result binding acknowledge: removed caseId=${binding.caseId} attemptId=${binding.attemptId}"}
  binding.leased.set(false)
 }
 fun release(binding:FactoryStepResultBinding){
  if(bindings[binding.caseId]===binding){binding.leased.set(false);logger.info{"Factory result binding release: available caseId=${binding.caseId} attemptId=${binding.attemptId}"}}
 }
 fun invalidate(binding:FactoryStepResultBinding){
  if(bindings.remove(binding.caseId,binding))logger.warn{"Factory result binding invalidate: removed caseId=${binding.caseId} attemptId=${binding.attemptId}"}
  binding.leased.set(false)
 }
 fun remove(caseId:UUID){bindings.remove(caseId)}
 internal fun contains(caseId:UUID)=bindings.containsKey(caseId)
 private fun validated(caseId:UUID,namespaceId:UUID,agentName:String,operation:String):FactoryStepResultBinding?{
  val binding=bindings[caseId]
  if(binding==null){logger.warn{"Factory result binding $operation: absent caseId=$caseId agent=$agentName"};return null}
  if(!binding.expiresAt.isAfter(clock.instant())){bindings.remove(caseId,binding);logger.warn{"Factory result binding $operation: expired caseId=$caseId attemptId=${binding.attemptId}"};return null}
  if(binding.namespaceId!=namespaceId){logger.warn{"Factory result binding $operation: namespace mismatch caseId=$caseId attemptId=${binding.attemptId}"};return null}
  if(binding.agentName!=agentName){logger.warn{"Factory result binding $operation: agent mismatch caseId=$caseId attemptId=${binding.attemptId} expected=${binding.agentName} actual=$agentName"};return null}
  return binding
 }
}
