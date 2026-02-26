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
  modulePathIgnorePatterns: ['<rootDir>/dist/', '<rootDir>/.nx/', '<rootDir>/libs/.*/dist/'],

  testPathIgnorePatterns: [
    '/node_modules/',
    '/dist/',
    '/.nx/',
    '.e2e-spec.(ts|js)$',
    '/apps/client-e2e/',
    '/apps/client/src/',
  ],

  // Allow ts-jest to transform ESM-only packages
  transformIgnorePatterns: ['node_modules/(?!(oauth4webapi)/)'],

  // Map TypeScript path aliases to source files (mirrors tsconfig.base.json paths)
  moduleNameMapper: {
    '^@coday/agent$': '<rootDir>/libs/agent/src/index.ts',
    '^@coday/ai$': '<rootDir>/libs/ai/src/index.ts',
    '^@coday/ai-thread$': '<rootDir>/libs/ai-thread/src/index.ts',
    '^@coday/coday-services$': '<rootDir>/libs/coday-services/src/index.ts',
    '^@coday/config$': '<rootDir>/libs/config/src/index.ts',
    '^@coday/core$': '<rootDir>/libs/core/src/index.ts',
    '^@coday/file$': '<rootDir>/libs/file/src/index.ts',
    '^@coday/function$': '<rootDir>/libs/function/src/index.ts',
    '^@coday/git$': '<rootDir>/libs/git/src/index.ts',
    '^@coday/handler$': '<rootDir>/libs/handler/src/index.ts',
    '^@coday/handlers$': '<rootDir>/libs/handlers/src/index.ts',
    '^@coday/handlers-config$': '<rootDir>/libs/handlers/config/src/index.ts',
    '^@coday/handlers-load$': '<rootDir>/libs/handlers/load/src/index.ts',
    '^@coday/handlers-looper$': '<rootDir>/libs/handlers/looper/src/index.ts',
    '^@coday/handlers-memory$': '<rootDir>/libs/handlers/memory/src/index.ts',
    '^@coday/handlers-openai$': '<rootDir>/libs/handlers/openai/src/index.ts',
    '^@coday/handlers-stats$': '<rootDir>/libs/handlers/stats/src/index.ts',
    '^@coday/integration$': '<rootDir>/libs/integration/src/index.ts',
    '^@coday/integrations-ai$': '<rootDir>/libs/integrations/ai/src/index.ts',
    '^@coday/integrations-file$': '<rootDir>/libs/integrations/file/src/index.ts',
    '^@coday/integrations-git$': '<rootDir>/libs/integrations/git/src/index.ts',
    '^@coday/integrations-http$': '<rootDir>/libs/integrations/http/src/index.ts',
    '^@coday/integrations-mcp$': '<rootDir>/libs/integrations/mcp/src/index.ts',
    '^@coday/load$': '<rootDir>/libs/load/src/index.ts',
    '^@coday/mcp$': '<rootDir>/libs/mcp/src/index.ts',
    '^@coday/memory$': '<rootDir>/libs/memory/src/index.ts',
    '^@coday/model$': '<rootDir>/libs/model/src/index.ts',
    '^@coday/repository$': '<rootDir>/libs/repository/src/index.ts',
    '^@coday/service$': '<rootDir>/libs/service/src/index.ts',
    '^@coday/utils$': '<rootDir>/libs/utils/src/index.ts',
  },

  // Useful for debugging
  verbose: true,
}
