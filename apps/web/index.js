#!/usr/bin/env node
import { fileURLToPath, pathToFileURL } from 'url';
import { dirname, resolve } from 'path';
import { existsSync } from 'fs';
import { createRequire } from 'module';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

/**
 * Coday Web Launcher
 * 
 * This script orchestrates the web interface by:
 * 1. Resolving the client package location at runtime
 * 2. Setting the client path for the server to use
 * 3. Delegating execution to the server package
 */

// Create require function for resolving packages
const require = createRequire(import.meta.url);

/**
 * Resolve the client package location
 * 
 * Tries multiple resolution strategies:
 * 1. Standard node_modules resolution (production/npx)
 * 2. Workspace protocol resolution (development)
 * 3. Relative path fallback (monorepo development)
 */
function resolveStaticPackagePath(packageName, developmentPath, displayName) {
  const developmentBrowserPath = resolve(__dirname, developmentPath);

  try {
    const packageJsonPath = require.resolve(`${packageName}/package.json`);
    const packagePath = dirname(packageJsonPath);
    const browserPath = resolve(packagePath, 'browser');

    if (existsSync(browserPath)) {
      return browserPath;
    }

    // Keep compatibility with packages exposing their browser build at the root.
    if (existsSync(resolve(packagePath, 'index.html'))) {
      return packagePath;
    }

    if (existsSync(developmentBrowserPath)) {
      console.log(`Using development ${displayName.toLowerCase()} build from monorepo`);
      return developmentBrowserPath;
    }

    console.error(`${displayName} package found but browser directory is missing.`);
    console.error(`Checked paths: ${browserPath}, ${packagePath}, ${developmentBrowserPath}`);
    process.exit(1);
  } catch (error) {
    if (existsSync(developmentBrowserPath)) {
      console.log(`Using development ${displayName.toLowerCase()} build from monorepo`);
      return developmentBrowserPath;
    }

    console.error(`Could not resolve ${packageName} package.`);
    console.error('Please ensure dependencies are installed and the application is built.');
    console.error('Error:', error.message);
    process.exit(1);
  }
}

// Resolve and expose static application paths to the server.
const clientPath = resolveStaticPackagePath('@whoz-oss/coday-client', '../client/dist/browser', 'Client');
const factoryCockpitPath = resolveStaticPackagePath(
  '@whoz-oss/coday-factory-cockpit',
  '../factory-cockpit/dist/browser',
  'Factory cockpit'
);
process.env.CODAY_CLIENT_PATH = clientPath;
process.env.CODAY_FACTORY_COCKPIT_PATH = factoryCockpitPath;

console.log(`Coday Web: Using client files from ${clientPath}`);
console.log(`Coday Web: Using factory cockpit files from ${factoryCockpitPath}`);

// Import and run the server
// The server package exports its main module which starts the server
try {
  // Try to resolve the server package
  const serverPackagePath = require.resolve('@whoz-oss/coday-server/package.json');
  const serverDir = dirname(serverPackagePath);
  const packagedServerMainPath = resolve(serverDir, 'server.js');
  const developmentServerMainPath = resolve(__dirname, '../server/dist/server.js');
  const serverMainPath = existsSync(packagedServerMainPath)
    ? packagedServerMainPath
    : developmentServerMainPath;

  if (!existsSync(serverMainPath)) {
    throw new Error(
      `Server entry point is missing. Checked paths: ${packagedServerMainPath}, ${developmentServerMainPath}`
    );
  }
  
  // Import the server module
  // Convert Windows absolute path to file:// URL for ESM import
  const serverModuleURL = pathToFileURL(serverMainPath).href;
  import(serverModuleURL).catch((error) => {
    console.error('Failed to start server:', error);
    process.exit(1);
  });
} catch (error) {
  console.error('Could not resolve @whoz-oss/coday-server package.');
  console.error('Please ensure dependencies are installed: pnpm install');
  console.error('Error:', error.message);
  process.exit(1);
}
