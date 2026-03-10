/** @type {import('ts-jest').JestConfigWithTsJest} */
export default {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['**/?(*.)+(spec|test).+(ts|js)'],
  transform: {
    '^.+\\.(ts|tsx)$': 'ts-jest',
  },
  // oauth4webapi is ESM-only, ts-jest needs to transform it
  transformIgnorePatterns: ['node_modules/(?!(oauth4webapi)/)'],
  moduleNameMapper: {
    '^@coday/model$': '<rootDir>/../../../libs/model/src/index.ts',
    '^@coday/service$': '<rootDir>/../../../libs/service/src/index.ts',
  },
  verbose: true,
}
