package io.whozoss.agentos.sdk.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ToolCategorySpec : StringSpec() {
    init {
        "READ_ONLY has low risk and VIEWER minimum role" {
            ToolCategory.READ_ONLY.riskLabel shouldBe "low risk"
            ToolCategory.READ_ONLY.minimumNamespaceRole shouldBe NamespaceRole.VIEWER
        }
        "WRITE has medium risk and MEMBER minimum role" {
            ToolCategory.WRITE.riskLabel shouldBe "medium risk"
            ToolCategory.WRITE.minimumNamespaceRole shouldBe NamespaceRole.MEMBER
        }
        "DESTRUCTIVE has high risk and ADMIN minimum role" {
            ToolCategory.DESTRUCTIVE.riskLabel shouldBe "high risk"
            ToolCategory.DESTRUCTIVE.minimumNamespaceRole shouldBe NamespaceRole.ADMIN
        }
        "ADMIN has critical risk and ADMIN minimum role" {
            ToolCategory.ADMIN.riskLabel shouldBe "critical"
            ToolCategory.ADMIN.minimumNamespaceRole shouldBe NamespaceRole.ADMIN
        }
        "categories are ordered by increasing risk" {
            val categories = ToolCategory.entries
            categories[0] shouldBe ToolCategory.READ_ONLY
            categories[1] shouldBe ToolCategory.WRITE
            categories[2] shouldBe ToolCategory.DESTRUCTIVE
            categories[3] shouldBe ToolCategory.ADMIN
        }
        "ordinals are strictly increasing: READ_ONLY < WRITE < DESTRUCTIVE < ADMIN" {
            (ToolCategory.READ_ONLY.ordinal < ToolCategory.WRITE.ordinal) shouldBe true
            (ToolCategory.WRITE.ordinal < ToolCategory.DESTRUCTIVE.ordinal) shouldBe true
            (ToolCategory.DESTRUCTIVE.ordinal < ToolCategory.ADMIN.ordinal) shouldBe true
        }
        "every category has a non-empty riskLabel" {
            ToolCategory.entries.forEach { category ->
                category.riskLabel.isNotBlank() shouldBe true
            }
        }
    }
}
