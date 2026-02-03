/** @type {import('ts-jest').JestConfigWithTsJest} */
import { pathsToModuleNameMapper } from 'ts-jest'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const tsconfig = require('./tsconfig.base.json')

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
  moduleNameMapper: pathsToModuleNameMapper(tsconfig.compilerOptions.paths ?? {}, { prefix: '<rootDir>/' }),

  // FORCE IGNORE PROBLEMATIC DIRECTORIES
  modulePathIgnorePatterns: ['<rootDir>/dist/', '<rootDir>/.nx/', '<rootDir>/libs/coday-events/dist/'],

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
