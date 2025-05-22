// eslint-disable-next-line @nx/enforce-module-boundaries
import baseConfig from '../../../eslint.config.js'

// Override the enforce-module-boundaries rule for this project
const modifiedConfig = baseConfig.map(config => {
  if (config.files && config.files.includes('**/*.ts') && config.rules && config.rules['@nx/enforce-module-boundaries']) {
    return {
      ...config,
      rules: {
        ...config.rules,
        '@nx/enforce-module-boundaries': 'off' // Temporarily disable this rule
      }
    }
  }
  return config
})

export default modifiedConfig