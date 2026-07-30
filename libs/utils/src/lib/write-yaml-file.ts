import * as yaml from 'yaml'
import { writeFileSync } from 'node:fs'

export function writeYamlFile(filePath: string, content: any): void {
  try {
    // lineWidth: 0 prevents line wrapping that breaks round-trip
    // blockQuote: false prevents | and > block scalars that cause parse failures
    // when content contains code/minified JS with YAML-significant characters (: # etc.)
    const stringified = yaml.stringify(content, { lineWidth: 0, blockQuote: false })
    writeFileSync(filePath, stringified)
  } catch (error) {
    console.error(`Failed to write file ${filePath}:`, error)
  }
}
