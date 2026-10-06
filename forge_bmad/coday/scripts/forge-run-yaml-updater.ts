import * as fs from 'fs'
import * as path from 'path'

export interface GateYamlUpdate {
  gate: 1 | 2 | 3 | 4
  decision: 'approved' | 'approved-with-changes'
  at: string
  completeRun?: boolean
}

function yamlString(value: string): string {
  return `'${value.replace(/'/g, "''")}'`
}

function upsertMappingFields(content: string, key: string, fields: Record<string, string>): string {
  const lines = content.replace(/\r\n/g, '\n').split('\n')
  const header = new RegExp(`^${key}:\\s*(?:#.*)?$`)
  const start = lines.findIndex((line) => header.test(line))
  if (start < 0) {
    const appended = [`${key}:`, ...Object.entries(fields).map(([name, value]) => `  ${name}: ${value}`)]
    return `${content.replace(/\s*$/, '')}\n${appended.join('\n')}\n`
  }

  let end = start + 1
  while (end < lines.length && (!lines[end].trim() || /^\s/.test(lines[end]) || lines[end].trimStart().startsWith('#')))
    end++
  for (const [name, value] of Object.entries(fields)) {
    const fieldPattern = new RegExp(`^  ${name}:`)
    const index = lines.slice(start + 1, end).findIndex((line) => fieldPattern.test(line))
    if (index >= 0) lines[start + 1 + index] = `  ${name}: ${value}`
    else {
      lines.splice(end, 0, `  ${name}: ${value}`)
      end++
    }
  }
  return lines.join('\n')
}

/** Updates only authoritative gate/outcome mappings and preserves all other text. */
export function updateForgeRunYamlAtomic(filePath: string, update: GateYamlUpdate): void {
  const original = fs.readFileSync(filePath, 'utf8')
  const gateKey = `gate_${update.gate}`
  const existingStarted = new RegExp(`^${gateKey}:\\s*$[\\s\\S]*?^  started_at:\\s*([^#\\n]+)`, 'm')
    .exec(original)?.[1]
    ?.trim()
  const startedAt =
    existingStarted && existingStarted !== 'null' && existingStarted !== '~' ? existingStarted : yamlString(update.at)

  let next = upsertMappingFields(original, gateKey, {
    started_at: startedAt,
    decided_at: yamlString(update.at),
    human_decision: yamlString(update.decision),
  })
  if (update.completeRun) next = upsertMappingFields(next, 'run_outcome', { status: 'completed' })

  const tempPath = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${process.pid}.${Date.now()}.tmp`)
  try {
    fs.writeFileSync(tempPath, next, 'utf8')
    fs.renameSync(tempPath, filePath)
  } catch (error) {
    try {
      fs.unlinkSync(tempPath)
    } catch {
      /* best effort */
    }
    throw error
  }
}
