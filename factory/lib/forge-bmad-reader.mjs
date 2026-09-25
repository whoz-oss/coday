// Stateless compatibility facade. The BMAD parsers live only in the generated
// operational bundle, built from the TypeScript sources
// `factory/src/domain/forge-bmad/forge-bmad-parser.ts` and
// `factory/src/adapters/forge/forge-bmad-file-reader.ts`.
export {
  readForgeRunYaml,
  readForgeRunYamlStrict,
  readStoryFrontmatter,
  readSprintStatus,
} from '../runtime/factory-operational.mjs'
