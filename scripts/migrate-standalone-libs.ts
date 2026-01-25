#!/usr/bin/env tsx

/**
 * Script to migrate standalone lib folders (without project.json) to proper Nx libraries
 * and update all imports across the project to use @coday/* paths
 */

import * as fs from 'fs'
import * as path from 'path'
import { execSync } from 'child_process'

const LIBS_DIR = path.join(process.cwd(), '../libs')
const TSCONFIG_BASE = path.join(process.cwd(), '../tsconfig.base.json')

interface StandaloneLib {
  libName: string
  packageName: string
  libPath: string
}

/**
 * Identify lib folders that don't have a project.json (not yet Nx libraries)
 */
function findStandaloneLibs(): StandaloneLib[] {
  const libs: StandaloneLib[] = []
  const items = fs.readdirSync(LIBS_DIR, { withFileTypes: true })

  for (const item of items) {
    // Skip files, only process directories
    if (!item.isDirectory()) {
      continue
    }

    // Skip hidden directories
    if (item.name.startsWith('.')) {
      continue
    }

    const libPath = path.join(LIBS_DIR, item.name)
    const projectJsonPath = path.join(libPath, 'project.json')

    // If no project.json, it's a standalone lib that needs migration
    if (!fs.existsSync(projectJsonPath)) {
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
 * Create Nx library structure for a standalone lib folder
 */
function createNxLibrary(lib: StandaloneLib): void {
  console.log(`\nCreating Nx structure for ${lib.libName}...`)

  // Check if src/ directory already exists
  const srcDir = path.join(lib.libPath, 'src')
  const hasSrc = fs.existsSync(srcDir)

  if (!hasSrc) {
    // No src/ directory - need to move all .ts files into src/
    console.log(`  No src/ directory found, creating structure...`)

    const files = fs.readdirSync(lib.libPath, { withFileTypes: true })
    const tsFiles = files.filter((f) => f.isFile() && f.name.endsWith('.ts'))

    if (tsFiles.length > 0) {
      fs.mkdirSync(srcDir, { recursive: true })

      for (const file of tsFiles) {
        const oldPath = path.join(lib.libPath, file.name)
        const newPath = path.join(srcDir, file.name)
        fs.renameSync(oldPath, newPath)
        console.log(`  Moved ${file.name} to src/`)
      }
    } else {
      console.log(`  Warning: No .ts files found in ${lib.libName}`)
    }
  } else {
    console.log(`  src/ directory already exists`)
  }

  // Create project.json
  const projectJson = {
    name: lib.libName,
    $schema: '../../node_modules/nx/schemas/project-schema.json',
    projectType: 'library',
    sourceRoot: `libs/${lib.libName}`,
    tags: ['scope:libs', 'type:core'],
    targets: {
      build: {
        executor: '@nx/js:tsc',
        outputs: ['{options.outputPath}'],
        options: {
          outputPath: `libs/${lib.libName}/dist`,
          main: `libs/${lib.libName}/index.ts`,
          tsConfig: `libs/${lib.libName}/tsconfig.lib.json`,
          assets: [`libs/${lib.libName}/*.md`],
        },
      },
    },
  }

  fs.writeFileSync(path.join(lib.libPath, 'project.json'), JSON.stringify(projectJson, null, 2) + '\n')
  console.log(`  Created project.json`)

  // Create index.ts barrel export if it doesn't exist
  const indexPath = path.join(lib.libPath, 'index.ts')
  if (!fs.existsSync(indexPath)) {
    fs.writeFileSync(indexPath, `export * from './src/index';\n`)
    console.log(`  Created index.ts`)
  }

  // Create tsconfig.lib.json
  const tsconfigLib = {
    extends: '../../tsconfig.base.json',
    compilerOptions: {
      outDir: './dist',
      declaration: true,
      declarationMap: true,
    },
    include: ['**/*.ts'],
    exclude: ['node_modules', 'dist', '**/*.spec.ts', '**/*.test.ts'],
  }

  fs.writeFileSync(path.join(lib.libPath, 'tsconfig.lib.json'), JSON.stringify(tsconfigLib, null, 2) + '\n')
  console.log(`  Created tsconfig.lib.json`)

  // Create README.md if it doesn't exist
  const readmePath = path.join(lib.libPath, 'README.md')
  if (!fs.existsSync(readmePath)) {
    const readme = `# ${lib.packageName}\n\nCore library for ${lib.libName}.\n`
    fs.writeFileSync(readmePath, readme)
    console.log(`  Created README.md`)
  }
}

/**
 * Extract imports from TypeScript files
 */
function extractImports(filePath: string): Set<string> {
  const imports = new Set<string>()

  if (!fs.existsSync(filePath)) {
    return imports
  }

  const content = fs.readFileSync(filePath, 'utf-8')

  // Match import statements: import ... from 'package' or import('package')
  const importRegex = /(?:import\s+(?:[\w{},\s*]+\s+from\s+)?['"]([^'"]+)['"]|import\s*\(\s*['"]([^'"]+)['"]\s*\))/g

  let match
  while ((match = importRegex.exec(content)) !== null) {
    const importPath = match[1] || match[2]
    if (importPath && !importPath.startsWith('.')) {
      // Extract package name (handle scoped packages)
      const packageName: any = importPath.startsWith('@')
        ? importPath.split('/').slice(0, 2).join('/')
        : importPath.split('/')[0]
      imports.add(packageName)
    }
  }

  return imports
}

/**
 * Recursively scan directory for TypeScript files and extract all imports
 */
function scanDirectoryForImports(dirPath: string): Set<string> {
  const allImports = new Set<string>()

  if (!fs.existsSync(dirPath)) {
    return allImports
  }

  const items = fs.readdirSync(dirPath, { withFileTypes: true })

  for (const item of items) {
    const fullPath = path.join(dirPath, item.name)

    if (item.isDirectory() && item.name !== 'node_modules' && item.name !== 'dist') {
      const subImports = scanDirectoryForImports(fullPath)
      subImports.forEach((imp) => allImports.add(imp))
    } else if (item.isFile() && (item.name.endsWith('.ts') || item.name.endsWith('.tsx'))) {
      const fileImports = extractImports(fullPath)
      fileImports.forEach((imp) => allImports.add(imp))
    }
  }

  return allImports
}

/**
 * Create package.json for a library with its dependencies
 */
function createPackageJson(lib: StandaloneLib): void {
  console.log(`  Analyzing dependencies for ${lib.libName}...`)

  const packageJsonPath = path.join(lib.libPath, 'package.json')

  // If package.json already exists, skip
  if (fs.existsSync(packageJsonPath)) {
    console.log(`  package.json already exists, skipping`)
    return
  }

  // Scan for imports
  const allImports = scanDirectoryForImports(lib.libPath)

  // Separate workspace deps and external deps
  const workspaceDeps = new Set<string>()
  const externalDeps = new Set<string>()

  for (const imp of allImports) {
    if (imp.startsWith('@coday/')) {
      if (imp !== lib.packageName) {
        // Don't add self-reference
        workspaceDeps.add(imp)
      }
    } else if (
      // Filter out built-in Node.js modules
      !imp.startsWith('node:') &&
      ![
        'fs',
        'path',
        'util',
        'events',
        'stream',
        'buffer',
        'crypto',
        'http',
        'https',
        'url',
        'os',
        'child_process',
      ].includes(imp)
    ) {
      externalDeps.add(imp)
    }
  }

  // Build dependencies object
  const dependencies: Record<string, string> = {}

  // Add workspace dependencies
  for (const dep of Array.from(workspaceDeps).sort()) {
    dependencies[dep] = 'workspace:*'
  }

  // Add external dependencies
  for (const dep of Array.from(externalDeps).sort()) {
    dependencies[dep] = 'catalog:'
  }

  // Always add tslib
  if (!dependencies['tslib']) {
    dependencies['tslib'] = 'catalog:'
  }

  // Create package.json structure
  const packageJson: any = {
    name: lib.packageName,
    version: '0.0.1',
    private: true,
    type: 'module',
    main: './src/index.js',
    types: './src/index.d.ts',
  }

  if (Object.keys(dependencies).length > 0) {
    packageJson.dependencies = dependencies
  }

  packageJson.devDependencies = {
    typescript: 'catalog:dev',
  }

  // Write package.json
  fs.writeFileSync(packageJsonPath, JSON.stringify(packageJson, null, 2) + '\n')

  console.log(`  Created package.json with ${workspaceDeps.size} workspace deps and ${externalDeps.size} external deps`)
}

/**
 * Update tsconfig.base.json paths to ensure consistent /* pattern
 */
function updateTsconfigPaths(libs: StandaloneLib[]): void {
  console.log('\nUpdating tsconfig.base.json paths...')

  const tsconfig = JSON.parse(fs.readFileSync(TSCONFIG_BASE, 'utf-8'))
  let modified = false

  for (const lib of libs) {
    const withStar = `${lib.packageName}/*`
    const withoutStar = lib.packageName

    // Remove old path mapping without /* if it exists
    if (tsconfig.compilerOptions.paths[withoutStar]) {
      delete tsconfig.compilerOptions.paths[withoutStar]
      console.log(`  Removed old path: ${withoutStar}`)
      modified = true
    }

    // Ensure path with /* exists
    if (!tsconfig.compilerOptions.paths[withStar]) {
      tsconfig.compilerOptions.paths[withStar] = [`libs/${lib.libName}/*`]
      console.log(`  Added path: ${withStar} -> libs/${lib.libName}/*`)
      modified = true
    } else {
      console.log(`  Path already exists: ${withStar}`)
    }
  }

  if (modified) {
    fs.writeFileSync(TSCONFIG_BASE, JSON.stringify(tsconfig, null, 2) + '\n')
    console.log('  Updated tsconfig.base.json')
  } else {
    console.log('  No changes needed in tsconfig.base.json')
  }
}

/**
 * Find all TypeScript files in the project (excluding node_modules, dist)
 */
function findAllTypeScriptFiles(): string[] {
  const files: string[] = []

  function scan(dir: string): void {
    if (!fs.existsSync(dir)) return

    const items = fs.readdirSync(dir, { withFileTypes: true })

    for (const item of items) {
      const fullPath = path.join(dir, item.name)

      if (item.isDirectory()) {
        if (['node_modules', 'dist', '.git', '.angular'].includes(item.name)) {
          continue
        }
        scan(fullPath)
      } else if (item.name.endsWith('.ts') || item.name.endsWith('.tsx')) {
        files.push(fullPath)
      }
    }
  }

  scan(process.cwd())
  return files
}

/**
 * Update imports in a file to use @coday/* paths
 */
function updateImportsInFile(filePath: string, migrations: Map<string, string>): boolean {
  let content = fs.readFileSync(filePath, 'utf-8')
  let modified = false

  for (const [oldImport, newImport] of migrations.entries()) {
    // Match various import patterns:
    // import ... from './path' or '../path' or 'libs/path'
    // import('./path') or import('../path')

    const patterns = [
      // Relative imports from same directory
      new RegExp(`from\\s+['"]\\./${oldImport}['"]`, 'g'),
      // Relative imports from parent directory
      new RegExp(`from\\s+['"]\\.\\./libs/${oldImport}['"]`, 'g'),
      new RegExp(`from\\s+['"]\\.\\./${oldImport}['"]`, 'g'),
      // Absolute from libs
      new RegExp(`from\\s+['"]libs/${oldImport}['"]`, 'g'),
      // Dynamic imports
      new RegExp(`import\\s*\\(\\s*['"]\\./${oldImport}['"]\\s*\\)`, 'g'),
      new RegExp(`import\\s*\\(\\s*['"]\\.\\./libs/${oldImport}['"]\\s*\\)`, 'g'),
      new RegExp(`import\\s*\\(\\s*['"]\\.\\./${oldImport}['"]\\s*\\)`, 'g'),
      new RegExp(`import\\s*\\(\\s*['"]libs/${oldImport}['"]\\s*\\)`, 'g'),
    ]

    for (const pattern of patterns) {
      if (pattern.test(content)) {
        content = content.replace(pattern, (match) => {
          if (match.includes('import(')) {
            return `import('${newImport}')`
          }
          return `from '${newImport}'`
        })
        modified = true
      }
    }
  }

  if (modified) {
    fs.writeFileSync(filePath, content)
  }

  return modified
}

/**
 * Update all imports across the project to use @coday/* paths
 */
function updateAllImports(libs: StandaloneLib[]): void {
  console.log('\nUpdating imports across the project...')

  // Build migration map: old relative path -> new @coday/* path
  const migrations = new Map<string, string>()

  for (const lib of libs) {
    // Map lib folder name to package name
    migrations.set(lib.libName, lib.packageName)
  }

  const allFiles = findAllTypeScriptFiles()
  let updatedCount = 0

  for (const filePath of allFiles) {
    if (updateImportsInFile(filePath, migrations)) {
      updatedCount++
      console.log(`  Updated imports in ${path.relative(process.cwd(), filePath)}`)
    }
  }

  console.log(`\n  Updated ${updatedCount} files with new imports`)
}

/**
 * Run Nx graph to update dependencies
 */
function updateNxGraph(): void {
  console.log('\nUpdating Nx project graph...')
  try {
    execSync('npx nx reset', { stdio: 'inherit' })
    console.log('  Nx cache reset')
  } catch (error) {
    console.warn('  Could not reset Nx cache (non-critical)')
  }
}

/**
 * Main execution
 */
function main() {
  console.log('Migrating standalone libs to Nx libraries...\n')

  const standaloneLibs = findStandaloneLibs()

  if (standaloneLibs.length === 0) {
    console.log('No standalone libs found. All libs are already Nx libraries!')
    return
  }

  console.log(`Found ${standaloneLibs.length} standalone lib(s) to migrate:`)
  standaloneLibs.forEach((lib) => console.log(`  - ${lib.libName} -> ${lib.packageName}`))

  // Step 1: Create Nx library structure for each standalone lib
  for (const lib of standaloneLibs) {
    createNxLibrary(lib)
    createPackageJson(lib)
  }

  // Step 1.5: Check existing Nx libs for missing package.json
  console.log('\nChecking existing Nx libraries for missing package.json...')
  const allLibs = fs
    .readdirSync(LIBS_DIR, { withFileTypes: true })
    .filter((item) => item.isDirectory() && !item.name.startsWith('.'))
    .map((item) => ({
      libName: item.name,
      packageName: `@coday/${item.name}`,
      libPath: path.join(LIBS_DIR, item.name),
    }))

  let createdCount = 0
  for (const lib of allLibs) {
    const packageJsonPath = path.join(lib.libPath, 'package.json')
    const projectJsonPath = path.join(lib.libPath, 'project.json')

    // Only process libs that have project.json (are Nx libs) but no package.json
    if (fs.existsSync(projectJsonPath) && !fs.existsSync(packageJsonPath)) {
      console.log(`  Creating package.json for ${lib.libName}...`)
      createPackageJson(lib)
      createdCount++
    }
  }

  if (createdCount === 0) {
    console.log('  All existing Nx libraries already have package.json')
  } else {
    console.log(`  Created ${createdCount} package.json file(s)`)
  }

  // Step 2: Update tsconfig.base.json paths
  updateTsconfigPaths(standaloneLibs)

  // Step 3: Update all imports across the project
  updateAllImports(standaloneLibs)

  // Step 4: Update Nx graph
  updateNxGraph()

  console.log('\nMigration complete!')
  console.log('\nNext steps:')
  console.log('  1. Run: tsx scripts/generate-lib-packages.ts')
  console.log('  2. Run: pnpm install')
  console.log('  3. Verify: nx graph')
  console.log('  4. Test: nx run-many -t build')
}

main()
