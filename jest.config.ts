import { readFileSync } from 'node:fs'
import { pathsToModuleNameMapper } from 'ts-jest'

const tsconfig = JSON.parse(readFileSync('./tsconfig.base.json', 'utf-8'))

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

  // Map @coday/* imports to source paths (from tsconfig) and ignore dist to avoid Haste collisions
  moduleNameMapper: pathsToModuleNameMapper(tsconfig.compilerOptions.paths, { prefix: '<rootDir>/' }),
  modulePathIgnorePatterns: ['<rootDir>/dist/', '<rootDir>/.nx/', '/dist/'],

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
