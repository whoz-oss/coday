import { existsSync, readFileSync } from 'fs'
import * as yaml from 'yaml'

export function readYamlFile<T>(filePath: string): T | undefined {
  try {
    if (!existsSync(filePath)) {
      return undefined
    }

    /* consider :
        import * as fs from "fs/promises"
        await fs.readFile(filePath, 'utf-8')
     */
    const content = readFileSync(filePath, 'utf-8')
    return yaml.parse(content) as T
  } catch (error) {
    console.error(`Failed to file at ${filePath} :`, error)
    return undefined
  }
}
