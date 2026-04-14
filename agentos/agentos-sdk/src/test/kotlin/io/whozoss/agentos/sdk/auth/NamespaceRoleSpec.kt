package io.whozoss.agentos.sdk.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NamespaceRoleSpec : StringSpec() {
    init {
        "OWNER satisfies all roles" {
            NamespaceRole.OWNER.satisfies(NamespaceRole.VIEWER) shouldBe true
            NamespaceRole.OWNER.satisfies(NamespaceRole.MEMBER) shouldBe true
            NamespaceRole.OWNER.satisfies(NamespaceRole.ADMIN) shouldBe true
            NamespaceRole.OWNER.satisfies(NamespaceRole.OWNER) shouldBe true
        }
        "VIEWER only satisfies VIEWER" {
            NamespaceRole.VIEWER.satisfies(NamespaceRole.VIEWER) shouldBe true
            NamespaceRole.VIEWER.satisfies(NamespaceRole.MEMBER) shouldBe false
            NamespaceRole.VIEWER.satisfies(NamespaceRole.ADMIN) shouldBe false
            NamespaceRole.VIEWER.satisfies(NamespaceRole.OWNER) shouldBe false
        }
        "hierarchy is strictly ordered" {
            NamespaceRole.MEMBER.satisfies(NamespaceRole.VIEWER) shouldBe true
            NamespaceRole.MEMBER.satisfies(NamespaceRole.ADMIN) shouldBe false
            NamespaceRole.ADMIN.satisfies(NamespaceRole.MEMBER) shouldBe true
            NamespaceRole.ADMIN.satisfies(NamespaceRole.OWNER) shouldBe false
        }
    }
}
