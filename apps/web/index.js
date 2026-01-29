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
function resolveClientPath() {
  try {
    // Try to resolve the client package using Node's module resolution
    const clientPackagePath = require.resolve('@whoz-oss/coday-client/package.json');
    const clientPath = dirname(clientPackagePath);
    
    // Check if browser directory exists
    const browserPath = resolve(clientPath, 'browser');
    if (existsSync(browserPath)) {
      return browserPath;
    }
    
    // Fallback: browser might be at package root for published package
    if (existsSync(resolve(clientPath, 'index.html'))) {
      return clientPath;
    }
    
    console.error('Client package found but browser directory is missing.');
    console.error(`Checked paths: ${browserPath}, ${clientPath}`);
    process.exit(1);
  } catch (error) {
    // Fallback for monorepo development
    const devClientPath = resolve(__dirname, '../../client/dist/browser');
    if (existsSync(devClientPath)) {
      console.log('Using development client build from monorepo');
      return devClientPath;
    }
    
    console.error('Could not resolve @whoz-oss/coday-client package.');
    console.error('Please ensure dependencies are installed: pnpm install');
    console.error('Error:', error.message);
    process.exit(1);
  }
}

// Resolve and set the client path
const clientPath = resolveClientPath();
process.env.CODAY_CLIENT_PATH = clientPath;

console.log(`Coday Web: Using client files from ${clientPath}`);

// Import and run the server
// The server package exports its main module which starts the server
try {
  // Try to resolve the server package
  const serverPackagePath = require.resolve('@whoz-oss/coday-server/package.json');
  const serverDir = dirname(serverPackagePath);
  const serverMainPath = resolve(serverDir, 'server.js');
  
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
