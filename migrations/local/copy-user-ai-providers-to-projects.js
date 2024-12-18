import { promises as fs } from 'fs'
import path from 'path'
import yaml from 'yaml'
import os from 'os'

const CODAY_DIR = path.join(os.homedir(), '.coday')
const USERS_DIR = path.join(CODAY_DIR, 'users')
const PROJECTS_DIR = path.join(CODAY_DIR, 'projects')

async function main() {
  console.log('Starting to apply single user aiProviders to projects')
  // 1. Get the only user expected
  const users = await fs.readdir(USERS_DIR)
  if (users.length !== 1) {
    throw new Error(`Expected exactly one user, found ${users.length}`)
  }
  const userName = users[0]

  // 2. Read and parse user YAML
  const userFilePath = path.join(USERS_DIR, userName, 'user.yaml')
  const userContent = await fs.readFile(userFilePath, 'utf-8')
  const user = yaml.parse(userContent)

  // 3. Extract aiProviders configuration
  const { aiProviders } = user
  if (!aiProviders) {
    throw new Error('No aiProviders configuration found in user config')
  }

  // 4. List all project directories
  const projects = await fs.readdir(PROJECTS_DIR)

  // 5. Process each project
  for (const projectName of projects) {
    const projectPath = path.join(PROJECTS_DIR, projectName)
    const stat = await fs.stat(projectPath)

    if (!stat.isDirectory()) {
      continue // Skip if not a directory
    }

    const projectFilePath = path.join(projectPath, 'project.yaml')
    try {
      // Read and parse project YAML
      const projectContent = await fs.readFile(projectFilePath, 'utf-8')
      const project = yaml.parse(projectContent)

      // Add aiProviders configuration
      project.aiProviders = aiProviders

      // Save updated project YAML
      await fs.writeFile(projectFilePath, yaml.stringify(project))
      console.log(`  Updated project: ${projectName}`)
    } catch (error) {
      console.error(`Error processing project ${projectName}:`, error)
    }
  }

  console.log('Ai providers config copied to all projects.')
}

main().catch(console.error)
