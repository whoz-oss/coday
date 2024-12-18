import * as path from 'node:path'
import * as yaml from 'yaml'
import * as fs from 'fs/promises'

async function addUsernameToThreads(projectsPath, username) {
  try {
    // Get all project directories
    const projects = (await fs.readdir(projectsPath, { withFileTypes: true }))
      .filter((p) => p.isDirectory())
      .map((p) => p.name)
    for (const project of projects) {
      console.log(`  in project ${project}`)
      const threadsPath = path.join(projectsPath, project, 'threads')

      try {
        // Get all thread files
        const threadFiles = (await fs.readdir(threadsPath, { withFileTypes: true }))
          .filter((p) => p.isFile())
          .map((p) => p.name)
        for (const file of threadFiles) {
          const filePath = path.join(threadsPath, file)

          try {
            // Read and parse file
            const content = (await fs.readFile(filePath, 'utf8')).toString()
            const data = yaml.parse(content)

            if (data && typeof data === 'object') {
              // Add username to the object
              const modified = { ...data, username }

              // Write back the modified content
              await fs.writeFile(filePath, yaml.stringify(modified))
              console.log(`    Updated ${filePath}`)
            } else {
              console.log(`    Invalid data structure in ${filePath}`)
            }
          } catch (fileErr) {
            console.log(`    Error processing file ${filePath}: ${fileErr}`)
          }
        }
      } catch (threadErr) {
        // Skip if threads directory doesn't exist
        continue
      }
    }

    console.log('Thread migration completed successfully')
  } catch (err) {
    console.log(`Error during thread migration: ${err}`)
    process.exit(1)
  }
}

// Get arguments
const projectsPath = process.argv[2]
const username = process.argv[3]
console.log(`Script inputs:
projectsPath: ${projectsPath}
username: ${username}`)

if (!projectsPath || !username) {
  console.error('Usage: node add-username-to-threads.js <projectsPath> <username>')
  // process.stdout.flush?.()
  process.exit(1)
}

addUsernameToThreads(projectsPath, username)
