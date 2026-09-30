package io.whozoss.agentos.git

import io.whozoss.agentos.exchange.ExchangeStorageService
import java.nio.file.Path
import java.util.UUID

/**
 * Internal bare repository of the namespace, beside its Exchange roots and never under them, so the
 * file APIs never expose it. The Exchange only knows its own roots; the Git layout belongs here.
 */
fun ExchangeStorageService.namespaceGitDirectory(namespaceId: UUID): Path =
    namespaceDirectory(namespaceId).resolve("repository.git")

/** Workspace-owned setup state, outside both browsable Exchange roots and the Git checkout. */
fun ExchangeStorageService.workspaceSupportDirectory(
    namespaceId: UUID,
    rootCaseId: UUID,
): Path = namespaceDirectory(namespaceId).resolve("workspace-support").resolve(rootCaseId.toString())

/** The namespace's directory, parent of its shared Exchange root. */
private fun ExchangeStorageService.namespaceDirectory(namespaceId: UUID): Path = namespaceRoot(namespaceId).parent
