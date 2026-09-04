package io.whozoss.agentos.skill

/**
 * A skill that an agent can read and invoke.
 *
 * Skills are scoped to a namespace via [FilesystemSkillRepository] which discovers them
 * from `SKILL.md` files under `<configPath>/skills/`.
 *
 * [name] and [description] are whitespace-collapsed and truncated by [FilesystemSkillRepository]
 * before storage. [body] is the full markdown content returned verbatim by [SkillReadTool].
 *
 * [skillRelativePath] is the path of the containing directory relative to the skills root
 * (e.g. `product/spec-writing`). The empty string means the skill lives directly in the
 * skills root.
 *
 * [resourceRoot] is the absolute path of the directory containing `SKILL.md`, used by
 * [SkillReadResourceTool] to resolve adjacent resource files. Null when the skill has no
 * bundled resources (DB-stored skills, future use).
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    /** Relative path of the skill directory under the skills root. */
    val skillRelativePath: String,
    /** Absolute path of the directory containing SKILL.md. Null when no resources are available. */
    val resourceRoot: String? = null,
)
