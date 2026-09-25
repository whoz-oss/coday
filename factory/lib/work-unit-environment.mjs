// Stateless compatibility facade. Work-unit environment descriptor vocabulary,
// lifecycle states, error codes and validations live only in the generated
// operational bundle, built from the TypeScript source
// `factory/src/domain/environment/work-unit-environment.ts`.
export {
  WORK_UNIT_ENVIRONMENT_STATES,
  WORK_UNIT_ENVIRONMENT_ERROR_CODES,
  validateNamespaceId,
  validateCanonicalAbsolutePath,
  validateGitRef,
  validateIsoInstant,
  validateWorkUnitEnvironment,
} from '../runtime/factory-operational.mjs'
