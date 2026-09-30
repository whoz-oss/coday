package io.whozoss.agentos.git

import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import kotlin.reflect.KClass

/**
 * Enables a spec only on POSIX systems. Managed Git execution is POSIX by design (a `/bin/sh`
 * askpass helper, POSIX permissions, `lsof`), and these specs spawn real shell processes.
 */
class PosixOnly : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean =
        !System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
