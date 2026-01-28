const nxPreset = require('@nx/jest/preset').default

module.exports = {
  ...nxPreset,
  testMatch: ['**/+(*.)+(spec|test).+(ts|js)?(x)'],
  transform: {
    '^.+\\.(ts|js|html)$': 'ts-jest',
  },
  moduleFileExtensions: ['ts', 'js', 'html'],
  coverageDirectory: `${process.env.NX_WORKSPACE_ROOT}/coverage/${process.env.NX_TASK_TARGET_PROJECT}`,
  coverageReporters: ['html'],

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
    '.*\\.e2e-spec\\.(ts|js)',
    '/apps/client-e2e/',
  ],
}
