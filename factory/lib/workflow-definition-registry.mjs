import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { hashWorkflowDefinition, validateWorkflowDefinition } from './workflow-definition.mjs'

export class WorkflowDefinitionRegistryError extends Error {
  constructor(code, details = {}) { super(code); this.name = 'WorkflowDefinitionRegistryError'; this.code = code; this.details = details }
}

export class WorkflowDefinitionRegistry {
  constructor(root) { this.root = root; this.loaded = null }

  async initialize() { await this.#load(); return this }
  async list() { return [...(await this.#load()).values()].sort((a, b) => a.workflowType.localeCompare(b.workflowType) || a.version.localeCompare(b.version, undefined, { numeric: true })) }
  async get(workflowType, version) { return (await this.#load()).get(`${workflowType}@${version}`) ?? null }

  async #load() {
    if (this.loaded) return this.loaded
    const definitions = new Map()
    let types = []
    try { types = await readdir(this.root, { withFileTypes: true }) } catch (error) { if (error.code !== 'ENOENT') throw error }
    for (const typeEntry of types.filter((entry) => entry.isDirectory()).sort((a, b) => a.name.localeCompare(b.name))) {
      const typeRoot = join(this.root, typeEntry.name)
      const files = (await readdir(typeRoot, { withFileTypes: true })).filter((entry) => entry.isFile() && entry.name.endsWith('.json')).sort((a, b) => a.name.localeCompare(b.name))
      for (const file of files) {
        let parsed
        try { parsed = JSON.parse(await readFile(join(typeRoot, file.name), 'utf8')) } catch { throw new WorkflowDefinitionRegistryError('INVALID_DEFINITION_FILE', { workflowType: typeEntry.name, file: file.name }) }
        const validated = validateWorkflowDefinition(parsed)
        if (!validated.ok) throw new WorkflowDefinitionRegistryError(validated.error.code, { ...validated.error, file: file.name })
        const expectedVersion = file.name.slice(0, -5)
        if (validated.definition.workflowType !== typeEntry.name || validated.definition.version !== expectedVersion) throw new WorkflowDefinitionRegistryError('DEFINITION_PATH_MISMATCH', { workflowType: typeEntry.name, version: expectedVersion })
        const definitionHash = hashWorkflowDefinition(validated.definition)
        const key = `${validated.definition.workflowType}@${validated.definition.version}`
        const existing = definitions.get(key)
        if (existing && existing.definitionHash !== definitionHash) throw new WorkflowDefinitionRegistryError('DEFINITION_COLLISION', { key })
        definitions.set(key, Object.freeze({ ...validated.definition, definitionHash }))
      }
    }
    this.loaded = definitions
    return definitions
  }
}
