import * as yaml from 'yaml'
import { writeFileSync } from 'node:fs'

export function writeYamlFile(filePath: string, content: any): void {
  try {
    const stringified = yaml.stringify(content, { lineWidth: 0 })
    writeFileSync(filePath, stringified)
  } catch (error) {
    console.error(`Failed to write file ${filePath}:`, error)
  }
}
