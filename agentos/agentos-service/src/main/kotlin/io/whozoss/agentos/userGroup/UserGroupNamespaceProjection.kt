package io.whozoss.agentos.userGroup

interface UserGroupNamespaceProjection {
    fun getUserGroup(): UserGroupNode
    fun getNamespaceExternalId(): String
}
