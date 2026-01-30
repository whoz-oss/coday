#!/usr/bin/env tsx

/**
 * Script to reorganize existing Nx libraries to follow proper structure:
 * - Ensure all .ts files are in src/lib/ directory
 * - Create src/index.ts that exports all modules from lib/
 * - Update project.json to point to src/index.ts
 * - Use git mv to preserve file history
 */

import * as fs from 'fs'
import * as path from 'path'
import { execSync } from 'child_process'

const LIBS_DIR = path.join(process.cwd(), 'libs')

interface NxLib {
  libName: string
  packageName: string
  libPath: string
}

/**
 * Move a file using git mv to preserve history
 */
function gitMove(oldPath: string, newPath: string): void {
  try {
    // Ensure target directory exists
    const targetDir = path.dirname(newPath)
    if (!fs.existsSync(targetDir)) {
      fs.mkdirSync(targetDir, { recursive: true })
    }

    // Use git mv to preserve history
    execSync(`git mv "${oldPath}" "${newPath}"`, { stdio: 'pipe' })
  } catch (error: any) {
    // If git mv fails (file not tracked, etc.), fall back to regular move
    if (error.message.includes('not under version control')) {
      console.log(`      Warning: ${path.basename(oldPath)} not tracked by git, using regular move`)
      fs.renameSync(oldPath, newPath)
    } else {
      throw error
    }
  }
}

/**
 * Find all Nx libraries (those with project.json)
 */
function findNxLibs(): NxLib[] {
  const libs: NxLib[] = []
  const items = fs.readdirSync(LIBS_DIR, { withFileTypes: true })

  for (const item of items) {
    if (!item.isDirectory() || item.name.startsWith('.')) {
      continue
    }

    const libPath = path.join(LIBS_DIR, item.name)
    const projectJsonPath = path.join(libPath, 'project.json')

    // Only process Nx libraries
    if (fs.existsSync(projectJsonPath)) {
      libs.push({
        libName: item.name,
        packageName: `@coday/${item.name}`,
        libPath,
      })
    }
  }

  return libs
}

/**
 * Get all .ts/.tsx files in a directory (non-recursive for root level)
 */
function getTsFilesInRoot(dirPath: string): string[] {
  if (!fs.existsSync(dirPath)) {
    return []
  }

  const items = fs.readdirSync(dirPath, { withFileTypes: true })
  return items
    .filter((item) => item.isFile() && (item.name.endsWith('.ts') || item.name.endsWith('.tsx')))
    .filter((item) => item.name !== 'index.ts') // Don't move index.ts
    .map((item) => item.name)
}

/**
 * Get all .ts/.tsx files in src/lib/ directory recursively
 */
function getTsFilesInSrcLib(
  srcLibPath: string,
  relativePath: string = ''
): Array<{ name: string; relativePath: string }> {
  if (!fs.existsSync(srcLibPath)) {
    return []
  }

  const files: Array<{ name: string; relativePath: string }> = []
  const items = fs.readdirSync(srcLibPath, { withFileTypes: true })

  for (const item of items) {
    const fullPath = path.join(srcLibPath, item.name)
    const relPath = path.join(relativePath, item.name)

    if (item.isDirectory()) {
      // Recursively scan subdirectories
      files.push(...getTsFilesInSrcLib(fullPath, relPath))
    } else if ((item.name.endsWith('.ts') || item.name.endsWith('.tsx')) && item.name !== 'index.ts') {
      files.push({
        name: item.name,
        relativePath: relPath,
      })
    }
  }

  return files
}

/**
 * Move root-level .ts files to src/lib/
 */
function moveFilesToSrc(lib: NxLib): boolean {
  const rootFiles = getTsFilesInRoot(lib.libPath)

  if (rootFiles.length === 0) {
    return false
  }

  console.log(`\n  Moving ${rootFiles.length} file(s) to src/lib/...`)

  const srcLibDir = path.join(lib.libPath, 'src', 'lib')
  if (!fs.existsSync(srcLibDir)) {
    fs.mkdirSync(srcLibDir, { recursive: true })
  }

  for (const fileName of rootFiles) {
    const oldPath = path.join(lib.libPath, fileName)
    const newPath = path.join(srcLibDir, fileName)

    // Check if file already exists in src/lib/
    if (fs.existsSync(newPath)) {
      console.log(`    Warning: ${fileName} already exists in src/lib/, skipping`)
      continue
    }

    gitMove(oldPath, newPath)
    console.log(`    Moved ${fileName} to src/lib/ (preserving git history)`)
  }

  return true
}

/**
 * Move files from src/ root to src/lib/ if they exist
 */
function moveSrcFilesToLib(lib: NxLib): boolean {
  const srcDir = path.join(lib.libPath, 'src')
  const srcLibDir = path.join(srcDir, 'lib')

  if (!fs.existsSync(srcDir)) {
    return false
  }

  // Get .ts files directly in src/ (not in subdirectories)
  const files = fs
    .readdirSync(srcDir, { withFileTypes: true })
    .filter((item) => item.isFile() && (item.name.endsWith('.ts') || item.name.endsWith('.tsx')))
    .filter((item) => item.name !== 'index.ts') // Don't move index.ts

  if (files.length === 0) {
    return false
  }

  console.log(`\n  Moving ${files.length} file(s) from src/ to src/lib/...`)

  if (!fs.existsSync(srcLibDir)) {
    fs.mkdirSync(srcLibDir, { recursive: true })
  }

  for (const file of files) {
    const oldPath = path.join(srcDir, file.name)
    const newPath = path.join(srcLibDir, file.name)

    // Check if file already exists in src/lib/
    if (fs.existsSync(newPath)) {
      console.log(`    Warning: ${file.name} already exists in src/lib/, skipping`)
      continue
    }

    gitMove(oldPath, newPath)
    console.log(`    Moved ${file.name} to src/lib/ (preserving git history)`)
  }

  return true
}

/**
 * Create or update src/index.ts to export from lib/
 */
function createSrcIndex(lib: NxLib): boolean {
  const srcDir = path.join(lib.libPath, 'src')
  const srcLibDir = path.join(srcDir, 'lib')
  const srcIndexPath = path.join(srcDir, 'index.ts')

  if (!fs.existsSync(srcLibDir)) {
    console.log(`    Warning: No src/lib/ directory found`)
    return false
  }

  // Get all .ts files in src/lib/ (excluding index.ts)
  const files = getTsFilesInSrcLib(srcLibDir)

  if (files.length === 0) {
    console.log(`    Warning: No .ts files found in src/lib/`)
    return false
  }

  // Generate exports from lib/
  const exports: string[] = []

  for (const file of files) {
    // Remove .ts/.tsx extension
    const modulePath = file.relativePath.replace(/\.tsx?$/, '')

    // Convert path separators to forward slashes for imports
    const importPath = './lib/' + modulePath.split(path.sep).join('/')

    exports.push(`export * from '${importPath}';`)
  }

  const content = exports.sort().join('\n') + '\n'

  // Check if file exists and has same content
  if (fs.existsSync(srcIndexPath)) {
    const existingContent = fs.readFileSync(srcIndexPath, 'utf-8')
    if (existingContent === content) {
      console.log(`    src/index.ts already up to date`)
      return false
    }
  }

  fs.writeFileSync(srcIndexPath, content)
  console.log(`    Created/updated src/index.ts with ${exports.length} export(s)`)

  return true
}

/**
 * Remove root index.ts if it exists (Nx uses src/index.ts directly)
 */
function removeRootIndex(lib: NxLib): boolean {
  const rootIndexPath = path.join(lib.libPath, 'index.ts')

  if (fs.existsSync(rootIndexPath)) {
    try {
      // Use git rm to properly remove from git
      execSync(`git rm "${rootIndexPath}"`, { stdio: 'pipe' })
      console.log(`    Removed root index.ts with git rm (Nx uses src/index.ts directly)`)
    } catch (error: any) {
      // If git rm fails, fall back to regular delete
      if (error.message.includes('not under version control')) {
        fs.unlinkSync(rootIndexPath)
        console.log(`    Removed root index.ts (Nx uses src/index.ts directly)`)
      } else {
        throw error
      }
    }
    return true
  }

  return false
}

/**
 * Update project.json to use correct entry point
 */
function updateProjectJson(lib: NxLib): boolean {
  const projectJsonPath = path.join(lib.libPath, 'project.json')

  if (!fs.existsSync(projectJsonPath)) {
    return false
  }

  const projectJson = JSON.parse(fs.readFileSync(projectJsonPath, 'utf-8'))
  let modified = false

  // Update main entry point to src/index.ts
  if (projectJson.targets?.build?.options?.main) {
    const expectedMain = `libs/${lib.libName}/src/index.ts`
    if (projectJson.targets.build.options.main !== expectedMain) {
      projectJson.targets.build.options.main = expectedMain
      modified = true
      console.log(`    Updated project.json main entry point to src/index.ts`)
    }
  }

  // Update sourceRoot
  const expectedSourceRoot = `libs/${lib.libName}`
  if (projectJson.sourceRoot !== expectedSourceRoot) {
    projectJson.sourceRoot = expectedSourceRoot
    modified = true
    console.log(`    Updated project.json sourceRoot`)
  }

  if (modified) {
    fs.writeFileSync(projectJsonPath, JSON.stringify(projectJson, null, 2) + '\n')
  }

  return modified
}

/**
 * Update imports in all .ts files to reflect new structure
 */
function updateImportsInLib(lib: NxLib): void {
  const srcLibDir = path.join(lib.libPath, 'src', 'lib')

  if (!fs.existsSync(srcLibDir)) {
    return
  }

  // Get all .ts files recursively
  function getAllTsFiles(dir: string): string[] {
    const files: string[] = []
    const items = fs.readdirSync(dir, { withFileTypes: true })

    for (const item of items) {
      const fullPath = path.join(dir, item.name)

      if (item.isDirectory()) {
        files.push(...getAllTsFiles(fullPath))
      } else if (item.name.endsWith('.ts') || item.name.endsWith('.tsx')) {
        files.push(fullPath)
      }
    }

    return files
  }

  const allFiles = getAllTsFiles(srcLibDir)
  let updatedCount = 0

  for (const filePath of allFiles) {
    let content = fs.readFileSync(filePath, 'utf-8')

    // Update relative imports that might have broken
    // from '../something' to './something' if both are now in src/lib/
    const lines = content.split('\n')
    const updatedLines = lines.map((line) => {
      // Match import statements with relative paths going up
      const match: any = line.match(/^(\s*(?:import|export).*from\s+['"])(\.\.\/.+)(['"])/)

      if (match) {
        const importPath = match[2]

        // If import goes up one level from src/lib/, it might need adjustment
        // This is a simple heuristic - you might need to adjust based on your actual structure
        if (importPath.startsWith('../') && !importPath.startsWith('../../')) {
          // Could be referencing something that's now in src/lib/
          // For now, just log it for manual review
          console.log(`      Note: Check import in ${path.relative(lib.libPath, filePath)}: ${importPath}`)
        }
      }

      return line
    })

    const newContent = updatedLines.join('\n')
    if (newContent !== content) {
      fs.writeFileSync(filePath, newContent)
      updatedCount++
    }
  }

  if (updatedCount > 0) {
    console.log(`    Updated imports in ${updatedCount} file(s)`)
  }
}

/**
 * Reorganize a single library
 */
function reorganizeLib(lib: NxLib): boolean {
  console.log(`\nReorganizing ${lib.libName}...`)

  let changed = false

  // Step 1: Move root .ts files to src/lib/
  if (moveFilesToSrc(lib)) {
    changed = true
  }

  // Step 1.5: Move files from src/ to src/lib/ if needed
  if (moveSrcFilesToLib(lib)) {
    changed = true
  }

  // Step 2: Create/update src/index.ts
  if (createSrcIndex(lib)) {
    changed = true
  }

  // Step 3: Remove root index.ts (Nx doesn't use it)
  if (removeRootIndex(lib)) {
    changed = true
  }

  // Step 4: Update project.json
  if (updateProjectJson(lib)) {
    changed = true
  }

  // Step 5: Update imports if structure changed
  if (changed) {
    updateImportsInLib(lib)
  }

  if (!changed) {
    console.log(`  Already follows Nx guidelines`)
  }

  return changed
}

/**
 * Main execution
 */
function main() {
  console.log('Reorganizing Nx libraries to follow guidelines...\n')

  const nxLibs = findNxLibs()

  if (nxLibs.length === 0) {
    console.log('No Nx libraries found.')
    return
  }

  console.log(`Found ${nxLibs.length} Nx library(ies):\n`)

  let reorganizedCount = 0

  for (const lib of nxLibs) {
    if (reorganizeLib(lib)) {
      reorganizedCount++
    }
  }

  console.log(`\n${'='.repeat(60)}`)

  if (reorganizedCount === 0) {
    console.log('All libraries already follow Nx guidelines!')
  } else {
    console.log(`Reorganized ${reorganizedCount} library(ies)`)
    console.log('\nNext steps:')
    console.log('  1. Review any import warnings above')
    console.log('  2. Run: nx run-many -t build')
    console.log('  3. Fix any compilation errors')
  }
}

main()
