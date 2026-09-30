package io.whozoss.agentos.git.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

class GitRemoteUrlValidatorSpec :
    StringSpec({

        val validator = GitRemoteUrlValidator(GitExecutionProperties())

        "an ordinary https repository URL is accepted" {
            validator.validate("https://github.com/whoz-oss/coday.git")
        }

        "a URL embedding credentials is refused" {
            val error = shouldThrow<InvalidGitRemoteException> { validator.validate("https://user:token@github.com/org/repo.git") }
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
                shouldThrow<InvalidGitRemoteException> { validator.validate(url) }
            }
        }

        "a URL starting with a dash is refused so it cannot be read as an option" {
            val error = shouldThrow<InvalidGitRemoteException> { validator.validate("--upload-pack=touch /tmp/pwned") }
            error.message!! shouldContain "must not start with '-'"
        }

        "a URL containing control characters is refused" {
            shouldThrow<InvalidGitRemoteException> { validator.validate("https://forge.example/repo.git\nrm -rf /") }
        }

        "a blank URL is refused" {
            shouldThrow<InvalidGitRemoteException> { validator.validate("   ") }
        }

        "a loopback host is refused by default" {
            val error = shouldThrow<InvalidGitRemoteException> { validator.validate("https://127.0.0.1/org/repo.git") }
            error.message!! shouldContain "private or loopback"
        }

        listOf("https://[fd00::1]/repo.git", "https://[fc00::1]/repo.git", "https://100.64.0.1/repo.git", "https://100.127.255.254/repo.git").forEach { url ->
            "private or shared address is refused by default: $url" {
                shouldThrow<InvalidGitRemoteException> { validator.validate(url) }
                GitRemoteUrlValidator(GitExecutionProperties(allowPrivateRemoteHosts = true)).validate(url)
            }
        }

        "noncanonical numeric addresses are rejected before DNS can interpret them differently" {
            listOf("0177.0.0.1", "0x7f000001", "127.1", "2130706433", "0x7f.0.0.1",
                "0300.0250.1.1", "192.168.01.1", "127.0.0.1.", "999.0.0.1", "0xffffffff").forEach { host ->
                // URI rejects some spellings before the host guard; every variant must fail closed.
                shouldThrow<InvalidGitRemoteException> { validator.validate("https://$host/repo.git") }
            }
            validator.validate("https://93.184.216.34/repo.git")
        }

        "a private host is allowed once the deployment opts in" {
            val permissive = GitRemoteUrlValidator(GitExecutionProperties(allowPrivateRemoteHosts = true))
            permissive.validate("https://10.0.0.5/org/repo.git")
        }
    })
