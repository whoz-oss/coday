package io.whozoss.agentos.permissions

/**
 * Represents the types of actions that can be performed on entities.
 * Used in permission checks to determine if a user can perform a specific action.
 */
enum class Action {
    /**
     * Read action - view/retrieve entity data
     */
    READ,

    /**
     * Write action - create/update entity data
     */
    WRITE,

    /**
     * Delete action - remove entity
     */
    DELETE
}