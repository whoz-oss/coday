export type SkillMetadata = {
  name: string
  description: string
  path: string
  entrypoint?: string
  requires?: { bins?: string[]; env?: string[] }
}
