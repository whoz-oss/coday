package io.whozoss.factory.capability

import io.whozoss.factory.config.FrontVerificationProperties
import io.whozoss.factory.verification.manifest.VerificationRunResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FactoryFrontVerificationRegistryTest {
    private val noSpawn = FrontCommandRunner { error("planning tests must not execute") }
    private val staticNx = NxProjectResolver { project, root ->
        val projectPath = Files.walk(root).use { paths ->
            paths.filter { it.fileName?.toString() == "project.json" }
                .filter { Files.readString(it).contains("\"name\":\"$project\"") }
                .findFirst()
                .orElseThrow()
        }
        val json = Files.readString(projectPath)
        NxProjectResolution(0, json, "", false)
    }

    @Test
    fun `positive build plan needs no target manifest`() {
        val root = repo(mapOf("ui" to listOf("frontend-test"), "aphrodite" to listOf("build")))
        Files.createDirectories(root.resolve("ui/src"))
        val registry = FactoryFrontVerificationRegistry(
            FrontVerificationProperties(buildHosts = listOf("aphrodite")), noSpawn, staticNx,
        )
        val ready = assertIs<FactoryFrontVerificationRegistry.PlanResult.Ready>(
            registry.plan("frontend-build", root, listOf("ui/src/button.ts")),
        )
        assertTrue(ready.plan.command.contains("--projects=aphrodite"))
        assertTrue(ready.plan.command.contains("--skip-nx-cache"))
    }

    @Test
    fun `positive test plan selects owner frontend-test target`() {
        val root = repo(mapOf("ui" to listOf("frontend-test")))
        Files.createDirectories(root.resolve("ui/src"))
        val registry = FactoryFrontVerificationRegistry(FrontVerificationProperties(), noSpawn, staticNx)
        val ready = assertIs<FactoryFrontVerificationRegistry.PlanResult.Ready>(
            registry.plan("frontend-tests", root, listOf("ui/src/button.ts")),
        )
        assertEquals(listOf("ui"), ready.plan.selectedProjects)
    }

    @Test
    fun `missing configured host fails closed`() {
        val root = repo(mapOf("ui" to listOf("frontend-test")))
        Files.createDirectories(root.resolve("ui/src"))
        val registry = FactoryFrontVerificationRegistry(
            FrontVerificationProperties(buildHosts = listOf("missing")), noSpawn, staticNx,
        )
        assertIs<FactoryFrontVerificationRegistry.PlanResult.Refused>(
            registry.plan("frontend-build", root, listOf("ui/src/button.ts")),
        )
    }

    @Test
    fun `inferred configured test target is accepted`() {
        val root = repo(mapOf("ui" to emptyList()))
        Files.createDirectories(root.resolve("ui/src"))
        val inferredNx = NxProjectResolver { project, _ ->
            NxProjectResolution(0, "{\"name\":\"$project\",\"targets\":{\"frontend-test\":{}}}", "", false)
        }
        val registry = FactoryFrontVerificationRegistry(FrontVerificationProperties(), noSpawn, inferredNx)
        val ready = assertIs<FactoryFrontVerificationRegistry.PlanResult.Ready>(
            registry.plan("frontend-tests", root, listOf("ui/src/button.ts")),
        )
        assertEquals(listOf("ui"), ready.plan.selectedProjects)
    }

    @Test
    fun `missing configured target reports its actual name`() {
        val root = repo(mapOf("ui" to emptyList()))
        Files.createDirectories(root.resolve("ui/src"))
        val registry = FactoryFrontVerificationRegistry(
            FrontVerificationProperties(testTarget = "frontend-test"), noSpawn, staticNx,
        )
        val refused = assertIs<FactoryFrontVerificationRegistry.PlanResult.Refused>(
            registry.plan("frontend-tests", root, listOf("ui/src/button.ts")),
        )
        assertTrue(refused.message.contains("does not expose configured Nx target 'frontend-test'"))
        assertTrue(!refused.message.contains("target 'test'"))
    }

    @Test
    fun `Nx resolver failure is refused as infrastructure`() {
        val root = repo(mapOf("ui" to emptyList()))
        Files.createDirectories(root.resolve("ui/src"))
        val failedNx = NxProjectResolver { _, _ -> NxProjectResolution(1, "", "boom", false) }
        val registry = FactoryFrontVerificationRegistry(FrontVerificationProperties(), noSpawn, failedNx)
        val refused = assertIs<FactoryFrontVerificationRegistry.PlanResult.Refused>(
            registry.plan("frontend-tests", root, listOf("ui/src/button.ts")),
        )
        assertEquals(FRONT_VERIFICATION_INFRASTRUCTURE, refused.code)
        assertTrue(refused.message.contains("Cannot inspect effective Nx configuration"))
    }

    @Test
    fun `malicious project name and escaping path are refused`() {
        val root = repo(mapOf("bad;echo" to listOf("frontend-test")))
        Files.createDirectories(root.resolve("bad;echo/src"))
        val registry = FactoryFrontVerificationRegistry(FrontVerificationProperties(), noSpawn, staticNx)
        assertIs<FactoryFrontVerificationRegistry.PlanResult.Refused>(
            registry.plan("frontend-tests", root, listOf("bad;echo/src/x.ts")),
        )
        assertIs<FactoryFrontVerificationRegistry.PlanResult.Refused>(
            registry.plan("frontend-tests", root, listOf("../outside.ts")),
        )
    }

    @Test
    fun `exit zero without Nx work is refused`() {
        val root = repo(mapOf("ui" to listOf("frontend-test")))
        Files.createDirectories(root.resolve("ui/src"))
        val runner = FrontCommandRunner { plan ->
            VerificationRunResult.Executed(plan.name, plan.command, plan.timeoutMs, 0, "", "", 1, false)
        }
        val registry = FactoryFrontVerificationRegistry(FrontVerificationProperties(), runner, staticNx)
        val result = registry.resolve("frontend-tests", root, listOf("ui/src/x.ts"))
        assertIs<FactoryFrontVerificationResolution.Refused>(result)
        assertEquals(FRONT_VERIFICATION_EMPTY_SUCCESS, result.code)
    }

    private fun repo(projects: Map<String, List<String>>) = Files.createTempDirectory("front-registry").also { root ->
        projects.forEach { (name, targets) ->
            val dir = root.resolve(name)
            Files.createDirectories(dir)
            val targetsJson = targets.joinToString(",") { "\"$it\":{}" }
            Files.writeString(dir.resolve("project.json"), "{\"name\":\"$name\",\"targets\":{$targetsJson}}")
        }
    }
}
