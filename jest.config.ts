/** @type {import('ts-jest').JestConfigWithTsJest} */
export default {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>'],
  testMatch: ['**/?(*.)+(spec|test).+(ts|js)'],
  transform: {
    '^.+\\.(ts|tsx)$': 'ts-jest'
  },
  moduleFileExtensions: ['cjs', 'js', 'json', 'jsx', 'mjs', 'node', 'ts', 'tsx'],
  extensionsToTreatAsEsm: ['.ts'],
  collectCoverage: true,
  coverageDirectory: 'coverage',
  coverageReporters: ['text', 'lcov'],
  coveragePathIgnorePatterns: ['/node_modules/', '/__tests__/', '/dist/'],
  
  // FORCE IGNORE PROBLEMATIC DIRECTORIES
  modulePathIgnorePatterns: [
    '<rootDir>/dist/',
    '<rootDir>/.nx/',
  ],
  
  testPathIgnorePatterns: [
    '/node_modules/',
    '/dist/',
    '/.nx/',
    '\\.e2e-spec\\.(ts|js)$',
  ],
  
  // Useful for debugging
  verbose: true,
}