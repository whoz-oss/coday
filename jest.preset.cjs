const nxPreset = require('@nx/jest/preset').default
const path = require('path')

// Get workspace root
const workspaceRoot = process.cwd()

module.exports = {
  ...nxPreset,
  testMatch: ['**/+(*.)+(spec|test).+(ts|js)?(x)'],
  transform: {
    '^.+\\.(ts|js|html)$': 'ts-jest',
  },
  moduleFileExtensions: ['ts', 'js', 'html'],
  coverageDirectory: `${process.env.NX_WORKSPACE_ROOT}/coverage/${process.env.NX_TASK_TARGET_PROJECT}`,
  coverageReporters: ['html'],
  
  // Mapping avec chemins absolus
  moduleNameMapper: {
    '^@coday/coday-events$': path.join(workspaceRoot, 'libs/coday-events/src/index.ts'),
    '^@coday/ai-thread/(.*)$': path.join(workspaceRoot, 'libs/ai-thread/$1'),
    '^@coday/core$': path.join(workspaceRoot, 'libs/coday.ts'), 
    '^@coday/model/(.*)$': path.join(workspaceRoot, 'libs/model/$1'),
    '^@coday/options$': path.join(workspaceRoot, 'libs/options.ts'),
    '^@coday/service/(.*)$': path.join(workspaceRoot, 'libs/service/$1'),
  },
  
  // Exclude problematic directories
  modulePathIgnorePatterns: [
    '<rootDir>/dist/',
    '<rootDir>/.nx/',
  ],
  
  // Don't watch these directories
  watchPathIgnorePatterns: [
    '<rootDir>/dist/',
    '<rootDir>/.nx/',
  ],
  
  // Ignore test files that should not be run by Jest
  testPathIgnorePatterns: [
    '/node_modules/',
    '/dist/',
    '/.nx/',
    '.*\\.e2e-spec\\.(ts|js)$',
  ],
}