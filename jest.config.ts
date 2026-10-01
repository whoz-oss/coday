import { pathsToModuleNameMapper } from 'ts-jest'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { compilerOptions } = require('./tsconfig.base.json')

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
  moduleNameMapper: pathsToModuleNameMapper(compilerOptions.paths, { prefix: '<rootDir>/' }),
  collectCoverage: true,
  coverageDirectory: 'coverage',
  coverageReporters: ['text', 'lcov'],
  coveragePathIgnorePatterns: ['/node_modules/', '/__tests__/', '/dist/'],

  // FORCE IGNORE PROBLEMATIC DIRECTORIES
  modulePathIgnorePatterns: ['<rootDir>/dist/', '<rootDir>/.nx/', '<rootDir>/libs/coday-events/dist/', '/dist/'],

  testPathIgnorePatterns: [
    '/node_modules/',
    '/dist/',
    '/.nx/',
    '.e2e-spec.(ts|js)$',
    '/apps/client-e2e/',
    '/apps/client/src/',
    '/libs/integrations/http/',
  ],

  // Useful for debugging
  verbose: true,
}
