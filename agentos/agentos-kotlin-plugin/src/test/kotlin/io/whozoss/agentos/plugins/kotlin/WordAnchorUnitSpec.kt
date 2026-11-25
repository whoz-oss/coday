package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WordAnchorUnitSpec :
    StringSpec({
        "finds the column of a whole word" {
            WordAnchor.find(lineText = "val agents = load(path)", word = "load") shouldBe 13
        }

        "ignores a word that is only a substring of another identifier" {
            WordAnchor.find(lineText = "val loader = reload()", word = "load") shouldBe null
        }

        "skips a substring occurrence and finds the later whole word" {
            WordAnchor.find(lineText = "reload(load)", word = "load") shouldBe 7
        }

        "starts searching at fromColumn" {
            WordAnchor.find(lineText = "load(load)", word = "load", fromColumn = 1) shouldBe 5
        }

        "is null when the word is absent" {
            WordAnchor.find(lineText = "val x = 1", word = "load") shouldBe null
        }

        "is null for a blank word" {
            WordAnchor.find(lineText = "val x = 1", word = " ") shouldBe null
        }

        "treats underscore and dollar as identifier characters" {
            WordAnchor.find(lineText = "load_all(\$load)", word = "load") shouldBe null
        }
    })
