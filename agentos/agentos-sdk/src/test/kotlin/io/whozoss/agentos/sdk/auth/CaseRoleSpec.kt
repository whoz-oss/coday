package io.whozoss.agentos.sdk.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CaseRoleSpec : StringSpec() {
    init {
        "OWNER satisfies all case roles" {
            CaseRole.OWNER.satisfies(CaseRole.OBSERVER) shouldBe true
            CaseRole.OWNER.satisfies(CaseRole.PARTICIPANT) shouldBe true
            CaseRole.OWNER.satisfies(CaseRole.OWNER) shouldBe true
        }
        "OBSERVER only satisfies OBSERVER" {
            CaseRole.OBSERVER.satisfies(CaseRole.OBSERVER) shouldBe true
            CaseRole.OBSERVER.satisfies(CaseRole.PARTICIPANT) shouldBe false
            CaseRole.OBSERVER.satisfies(CaseRole.OWNER) shouldBe false
        }
        "PARTICIPANT satisfies OBSERVER and PARTICIPANT but not OWNER" {
            CaseRole.PARTICIPANT.satisfies(CaseRole.OBSERVER) shouldBe true
            CaseRole.PARTICIPANT.satisfies(CaseRole.PARTICIPANT) shouldBe true
            CaseRole.PARTICIPANT.satisfies(CaseRole.OWNER) shouldBe false
        }
    }
}
