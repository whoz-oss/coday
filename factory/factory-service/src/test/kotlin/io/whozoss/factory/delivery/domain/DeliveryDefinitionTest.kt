package io.whozoss.factory.delivery.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests of the pure delivery-definition domain. */
class DeliveryDefinitionTest {

    @Test
    fun `default delivery definition validates`() {
        val validated = validateDeliveryDefinition(defaultDeliveryDefinition().toMap())
        assertThat(validated).isInstanceOf(DeliveryDefinitionValidation.Valid::class.java)
        val definition = (validated as DeliveryDefinitionValidation.Valid).definition
        assertThat(definition.checkpoints.map { it.stage }).isEqualTo(DeliveryDefinitionSchema.STAGES)
        assertThat(definition.deliveryType).isEqualTo("factory-delivery")
    }

    @Test
    fun `definition hash is stable across repeated construction`() {
        assertThat(hashDeliveryDefinition(defaultDeliveryDefinition()))
            .isEqualTo(hashDeliveryDefinition(defaultDeliveryDefinition()))
    }

    @Test
    fun `canonical hashing is independent of key order`() {
        assertThat(CanonicalHash.sha256(mapOf("b" to 1, "a" to 2)))
            .isEqualTo(CanonicalHash.sha256(mapOf("a" to 2, "b" to 1)))
    }

    @Test
    fun `canonical hashing drops null values and keeps array order`() {
        assertThat(CanonicalHash.sha256(mapOf("a" to null, "b" to listOf(1, 2))))
            .isEqualTo(CanonicalHash.sha256(mapOf("b" to listOf(1, 2))))
    }

    @Test
    fun `unknown top-level field is rejected`() {
        val raw = defaultDeliveryDefinition().toMap() + mapOf("extra" to true)
        assertThat(validateDeliveryDefinition(raw)).isInstanceOf(DeliveryDefinitionValidation.Invalid::class.java)
    }

    @Test
    fun `release checkpoint requires a human responsibility`() {
        val raw = mutateCheckpoint(index = 2) { checkpoint ->
            checkpoint + mapOf("responsibility" to mapOf("kind" to "code", "name" to "release-approver"))
        }
        val result = validateDeliveryDefinition(raw)
        assertThat(result).isInstanceOf(DeliveryDefinitionValidation.Invalid::class.java)
        assertThat((result as DeliveryDefinitionValidation.Invalid).reason).isEqualTo("release_requires_human")
    }

    @Test
    fun `checkpoints must follow the governed stage order`() {
        val raw = mutateCheckpoint(index = 0) { checkpoint -> checkpoint + mapOf("stage" to "artifact-ready") }
        assertThat(validateDeliveryDefinition(raw)).isInstanceOf(DeliveryDefinitionValidation.Invalid::class.java)
    }

    @Test
    fun `invalid schema version is rejected`() {
        val raw = defaultDeliveryDefinition().toMap() + mapOf("schemaVersion" to "2")
        assertThat(validateDeliveryDefinition(raw)).isInstanceOf(DeliveryDefinitionValidation.Invalid::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutateCheckpoint(index: Int, transform: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
        val base = HashMap(defaultDeliveryDefinition().toMap())
        val checkpoints = (base["checkpoints"] as List<*>).toMutableList()
        checkpoints[index] = transform(checkpoints[index] as Map<String, Any?>)
        base["checkpoints"] = checkpoints
        return base
    }
}
