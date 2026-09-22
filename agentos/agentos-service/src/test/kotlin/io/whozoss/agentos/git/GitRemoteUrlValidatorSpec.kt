package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException

class GitRemoteUrlValidatorSpec :
    StringSpec({

        val validator = GitRemoteUrlValidator(GitExecutionProperties())

        "an ordinary https repository URL is accepted" {
            validator.validate("https://github.com/whoz-oss/coday.git")
        }

        "a URL embedding credentials is refused" {
            val error = shouldThrow<BadRequestException> { validator.validate("https://user:token@github.com/org/repo.git") }
            error.message!! shouldContain "must not embed credentials"
        }

        "non-https transports are refused" {
            listOf(
                "ssh://git@github.com/org/repo.git",
                "git://github.com/org/repo.git",
                "file:///etc/passwd",
                "ext::sh -c whoami",
                "/local/path/repo.git",
                "git@github.com:org/repo.git",
            ).forEach { url ->
                shouldThrow<BadRequestException> { validator.validate(url) }
            }
        }

        "a URL starting with a dash is refused so it cannot be read as an option" {
            val error = shouldThrow<BadRequestException> { validator.validate("--upload-pack=touch /tmp/pwned") }
            error.message!! shouldContain "must not start with '-'"
        }

        "a URL containing control characters is refused" {
            shouldThrow<BadRequestException> { validator.validate("https://forge.example/repo.git\nrm -rf /") }
        }

        "a blank URL is refused" {
            shouldThrow<BadRequestException> { validator.validate("   ") }
        }

        "a loopback host is refused by default" {
            val error = shouldThrow<BadRequestException> { validator.validate("https://127.0.0.1/org/repo.git") }
            error.message!! shouldContain "private or loopback"
        }

        "a private host is allowed once the deployment opts in" {
            val permissive = GitRemoteUrlValidator(GitExecutionProperties(allowPrivateRemoteHosts = true))
            permissive.validate("https://10.0.0.5/org/repo.git")
        }
    })
