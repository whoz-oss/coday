const nxPreset = require('@nx/jest/preset').default
module.exports = {
  ...nxPreset,
  testMatch: ['**/+(*.)+(spec|test).+(ts|js)?(x)'],
  transform: {
    '^.+\\.(ts|js|html)$': 'ts-jest',
  },
  resolver: '@nx/jest/plugins/resolver',
  moduleFileExtensions: ['ts', 'js', 'html'],
  coverageDirectory: `${process.env.NX_WORKSPACE_ROOT}/coverage/${process.env.NX_TASK_TARGET_PROJECT}`,
  coverageReporters: ['html'],
}
