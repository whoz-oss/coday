/**
 * Migrates a Coday TypeScript HTTP integration to the AgentOS HTTP_API plugin.
 *
 * Usage (from the repository root):
 *   npx tsx scripts/http-integration-to-openapi.ts <coday.yaml> <INTEGRATION_NAME>            # OpenAPI document
 *   npx tsx scripts/http-integration-to-openapi.ts <coday.yaml> <INTEGRATION_NAME> --config   # IntegrationConfig
 *
 * Reads `integration.<INTEGRATION_NAME>.http` of the project YAML and prints, on stdout, either the OpenAPI
 * 3.0.3 document (default) or the AgentOS IntegrationConfig YAML skeleton whose `spec.inline` embeds it.
 * Warnings about endpoints that could not be carried over as is go to stderr.
 */
import { readFileSync } from 'fs'
import * as yaml from 'yaml'
import { convertHttpIntegration, HttpIntegrationConfig, toYaml } from './utils/http-integration-to-openapi'

function main(): void {
  const args = process.argv.slice(2)
  const printConfig = args.includes('--config')
  const [file, name] = args.filter((arg) => arg !== '--config')
  if (!file || !name) {
    console.error('Usage: npx tsx scripts/http-integration-to-openapi.ts <coday.yaml> <INTEGRATION_NAME> [--config]')
    process.exit(2)
  }
  const project = yaml.parse(readFileSync(file, 'utf8')) as {
    integration?: Record<string, { http?: HttpIntegrationConfig }>
  }
  const http = project.integration?.[name]?.http
  if (!http?.baseUrl) {
    console.error(`integration.${name}.http.baseUrl not found in ${file}`)
    process.exit(1)
  }
  const { openApi, integrationConfig, warnings } = convertHttpIntegration(name, http)
  warnings.forEach((warning) => console.error(`warning: ${warning}`))
  process.stdout.write(toYaml(printConfig ? integrationConfig : openApi))
}

main()
