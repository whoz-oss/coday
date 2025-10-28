/** @type {import('ts-jest').JestConfigWithTsJest} */
export default {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>'],
  testMatch: ['**/?(*.)+(spec|test).+(ts|js)'],
  transform: {
    '^.+\\.(ts|tsx)$': 'ts-jest',
  },
  moduleFileExtensions: ['cjs', 'js', 'json', 'jsx', 'mjs', 'node', 'ts', 'tsx'],
  extensionsToTreatAsEsm: ['.ts'],
  collectCoverage: true,
  coverageDirectory: 'coverage',
  coverageReporters: ['text', 'lcov'],
  coveragePathIgnorePatterns: ['/node_modules/', '/__tests__/', '/dist/'],

  // FORCE IGNORE PROBLEMATIC DIRECTORIES
  modulePathIgnorePatterns: ['<rootDir>/dist/', '<rootDir>/.nx/', '<rootDir>/libs/coday-events/dist/'],

  // Module name mapping
  moduleNameMapper: {
    '^@coday/coday-events$': '<rootDir>/libs/coday-events/src/index.ts',
    '^@coday/service/(.*)$': '<rootDir>/libs/service/$1',
    '^@coday/utils/(.*)$': '<rootDir>/libs/utils/$1',
  },

  testPathIgnorePatterns: [
    '/node_modules/',
    '/dist/',
    '/.nx/',
    '.e2e-spec.(ts|js)$',
    '/apps/client-e2e/',
    '/apps/client/src/',
  ],

  // Useful for debugging
  verbose: true,
}
