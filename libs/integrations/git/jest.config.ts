/** @type {import('ts-jest').JestConfigWithTsJest} */
export default {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['**/?(*.)+(spec|test).+(ts|js)'],
  transform: {
    '^.+\\.(ts|tsx)$': 'ts-jest',
  },
  modulePathIgnorePatterns: ['<rootDir>/dist/'],
  moduleNameMapper: {
    '^@coday/model$': '<rootDir>/../../../libs/model/src/index.ts',
    '^@coday/service$': '<rootDir>/../../../libs/service/src/index.ts',
    '^@coday/function$': '<rootDir>/../../../libs/function/src/index.ts',
    '^@coday/utils$': '<rootDir>/../../../libs/utils/src/index.ts',
    '^@coday/repository$': '<rootDir>/../../../libs/repository/src/index.ts',
  },
}
