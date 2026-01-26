#!/usr/bin/env tsx

/**
 * Script to generate package.json files for all libs based on their actual dependencies
 * Analyzes TypeScript imports to detect both @coday/* workspace deps and external npm packages
 */

import * as fs from 'fs'
import * as path from 'path'

interface PackageJson {
  name: string
  version: string
  private: boolean
  type: string
  main: string
  types: string
  dependencies?: Record<string, string>
  devDependencies?: Record<string, string>
}

interface ProjectJson {
  name: string
  sourceRoot?: string
  targets?: {
    build?: {
      options?: {
        main?: string
      }
    }
  }
}

const HANDLERS_DIR = path.join(process.cwd(), 'libs/handlers')
const INTEGRATIONS_DIR = path.join(process.cwd(), 'libs/integrations')
const TSCONFIG_BASE = path.join(process.cwd(), 'tsconfig.base.json')

// Read the main package.json to get available external dependencies
const rootPackageJson = JSON.parse(fs.readFileSync(path.join(process.cwd(), 'package.json'), 'utf-8'))

// Read tsconfig.base.json to get path mappings
const tsconfigBase = JSON.parse(fs.readFileSync(TSCONFIG_BASE, 'utf-8'))
const pathMappings: Record<string, string[]> = tsconfigBase.compilerOptions.paths || {}

// Extract all @coday/* packages from path mappings
const codayPackages = new Set(
  Object.keys(pathMappings)
    .filter((key) => key.startsWith('@coday/'))
    .map((key) => key.replace('/*', '').replace('*', ''))
)

console.log('Found @coday packages:', Array.from(codayPackages))

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
      // For @coday/integrations-* and @coday/handlers-*, use the full path
      if (importPath.startsWith('@coday/integrations-') || importPath.startsWith('@coday/handlers-')) {
        // Keep the full import path (e.g., @coday/integrations-git)
        const fullPath = importPath.split('/').slice(0, 3).join('/')
        imports.add(fullPath)
      } else {
        // Extract package name (handle scoped packages)
        const packageName = importPath.startsWith('@')
          ? importPath.split('/').slice(0, 2).join('/')
          : importPath.split('/')[0]
        imports.add(packageName)
      }
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
 * Categorize imports into workspace deps and external deps
 */
function categorizeImports(imports: Set<string>): {
  workspaceDeps: Set<string>
  externalDeps: Set<string>
} {
  const workspaceDeps = new Set<string>()
  const externalDeps = new Set<string>()

  for (const imp of imports) {
    if (imp.startsWith('@coday/')) {
      workspaceDeps.add(imp)
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

  return { workspaceDeps, externalDeps }
}

/**
 * Get dependency version from root package.json or pnpm-workspace.yaml catalog
 */
function getDependencyVersion(packageName: string): string {
  // Check in dependencies
  if (rootPackageJson.dependencies?.[packageName]) {
    return 'catalog:'
  }

  // Check in devDependencies
  if (rootPackageJson.devDependencies?.[packageName]) {
    return 'catalog:'
  }

  console.warn(`Warning: Package ${packageName} not found in root package.json, using "catalog:"`)
  return 'catalog:'
}

/**
 * Generate or update package.json for a library
 */
function generatePackageJson(libPath: string, libName: string): void {
  const projectJsonPath = path.join(libPath, 'project.json')

  if (!fs.existsSync(projectJsonPath)) {
    console.log(`Skipping ${libName}: no project.json found`)
    return
  }

  const projectJson: ProjectJson = JSON.parse(fs.readFileSync(projectJsonPath, 'utf-8'))
  const packageName = `@coday/${libName}`

  console.log(`\nProcessing ${packageName}...`)

  // Scan for imports
  const sourceRoot = projectJson.sourceRoot || libPath
  const allImports = scanDirectoryForImports(sourceRoot)
  const { workspaceDeps, externalDeps } = categorizeImports(allImports)

  console.log(`  Workspace deps:`, Array.from(workspaceDeps))
  console.log(`  External deps:`, Array.from(externalDeps))

  // Build dependencies object
  const dependencies: Record<string, string> = {}

  // Add workspace dependencies
  for (const dep of Array.from(workspaceDeps).sort()) {
    if (dep !== packageName) {
      // Don't add self-reference
      dependencies[dep] = 'workspace:*'
    }
  }

  // Add external dependencies
  for (const dep of Array.from(externalDeps).sort()) {
    dependencies[dep] = getDependencyVersion(dep)
  }

  // Always add tslib
  if (!dependencies['tslib']) {
    dependencies['tslib'] = 'catalog:'
  }

  // Create package.json structure
  const packageJson: PackageJson = {
    name: packageName,
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
  const packageJsonPath = path.join(libPath, 'package.json')
  fs.writeFileSync(packageJsonPath, JSON.stringify(packageJson, null, 2) + '\n')

  console.log(`  ✓ Generated ${packageJsonPath}`)
}

/**
 * Process a directory of libraries
 */
function processLibraryDirectory(dirPath: string, dirName: string) {
  console.log(`\nProcessing ${dirName}...`)

  if (!fs.existsSync(dirPath)) {
    console.log(`  Skipping ${dirName}: directory not found`)
    return
  }

  const libs = fs
    .readdirSync(dirPath, { withFileTypes: true })
    .filter((item) => item.isDirectory())
    .map((item) => item.name)

  for (const lib of libs) {
    const libPath = path.join(dirPath, lib)
    generatePackageJson(libPath, lib)
  }
}

/**
 * Main execution
 */
function main() {
  console.log('Generating package.json files for all libs...\n')

  processLibraryDirectory(HANDLERS_DIR, 'handlers')
  processLibraryDirectory(INTEGRATIONS_DIR, 'integrations')

  console.log('\n✓ Done!')
}

main()
