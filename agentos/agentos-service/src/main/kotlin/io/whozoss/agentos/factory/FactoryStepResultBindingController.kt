package io.whozoss.agentos.factory

import io.whozoss.agentos.caseFlow.CaseService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class FactoryStepResultBindingRequest(val namespaceId:UUID,val agentName:String,val attemptId:String,val runtimeId:String,val capabilityToken:String,val expiresAt:Instant)

@RestController
@RequestMapping("/internal/factory")
class FactoryStepResultBindingController(private val registry:FactoryStepResultBindingRegistry,private val caseService:CaseService,@Value("\${agentos.factory.binding-secret:}") private val secret:String){
 @PutMapping("/cases/{caseId}/step-result-binding")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 fun bind(@PathVariable caseId:UUID,@RequestHeader("x-factory-agentos-secret") supplied:String?,@RequestBody request:FactoryStepResultBindingRequest){
  if(secret.isBlank()||supplied==null||!MessageDigest.isEqual(secret.toByteArray(),supplied.toByteArray()))throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
  val case=caseService.findById(caseId,false)?:throw ResponseStatusException(HttpStatus.NOT_FOUND)
  if(case.namespaceId!=request.namespaceId||request.agentName.isBlank()||request.attemptId.isBlank()||request.runtimeId.isBlank())throw ResponseStatusException(HttpStatus.CONFLICT)
  registry.bind(FactoryStepResultBinding(caseId,request.namespaceId,request.agentName,request.attemptId,request.runtimeId,request.capabilityToken,request.expiresAt))
 }
}
