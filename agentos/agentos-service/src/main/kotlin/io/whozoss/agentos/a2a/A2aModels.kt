package io.whozoss.agentos.a2a

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aAgentCard(
    val name: String,
    val description: String,
    val supportedInterfaces: List<A2aAgentInterface>,
    val version: String,
    val capabilities: A2aAgentCapabilities,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val skills: List<A2aAgentSkill>,
    val provider: A2aAgentProvider? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aAgentInterface(
    val url: String,
    val protocolBinding: String,
    val protocolVersion: String,
    val tenant: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aAgentCapabilities(
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aAgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val examples: List<String> = emptyList(),
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aAgentProvider(
    val url: String,
    val organization: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aTask(
    val id: String,
    val contextId: String? = null,
    val status: A2aTaskStatus,
    val artifacts: List<A2aArtifact>? = null,
    val history: List<A2aMessage>? = null,
    val metadata: Map<String, Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aTaskStatus(
    val state: String,
    val message: A2aMessage? = null,
    val timestamp: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aMessage(
    val messageId: String,
    val role: String,
    val parts: List<A2aPart>,
    val contextId: String? = null,
    val taskId: String? = null,
    val metadata: Map<String, Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aPart(
    val text: String? = null,
    val mediaType: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aArtifact(
    val artifactId: String,
    val name: String? = null,
    val description: String? = null,
    val parts: List<A2aPart>,
    val metadata: Map<String, Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aSendMessageRequest(
    val message: A2aMessage,
    val configuration: A2aSendMessageConfiguration? = null,
    val metadata: Map<String, Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aSendMessageConfiguration(
    val acceptedOutputModes: List<String>? = null,
    val historyLength: Int? = null,
    val returnImmediately: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aSendMessageResponse(
    val task: A2aTask? = null,
    val message: A2aMessage? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class A2aError(
    val code: String,
    val message: String,
    val details: List<Map<String, Any?>>? = null,
)
