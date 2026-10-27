package io.whozoss.factory.web

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CidrBlockTest {

    @Test
    fun `ipv4 network matches addresses inside and rejects outside`() {
        val block = CidrBlock.parse("172.16.0.0/12")

        assertThat(block.contains("172.16.0.1")).isTrue()
        assertThat(block.contains("172.18.0.3")).isTrue()
        assertThat(block.contains("172.31.255.255")).isTrue()
        assertThat(block.contains("172.32.0.1")).isFalse()
        assertThat(block.contains("172.15.255.255")).isFalse()
        assertThat(block.contains("10.0.0.1")).isFalse()
    }

    @Test
    fun `non byte aligned prefix is honoured`() {
        val block = CidrBlock.parse("192.168.10.128/25")

        assertThat(block.contains("192.168.10.128")).isTrue()
        assertThat(block.contains("192.168.10.255")).isTrue()
        assertThat(block.contains("192.168.10.127")).isFalse()
    }

    @Test
    fun `bare address is a single host`() {
        val block = CidrBlock.parse("10.1.2.3")

        assertThat(block.contains("10.1.2.3")).isTrue()
        assertThat(block.contains("10.1.2.4")).isFalse()
    }

    @Test
    fun `zero prefix matches every address of the same family`() {
        val block = CidrBlock.parse("0.0.0.0/0")

        assertThat(block.contains("203.0.113.10")).isTrue()
        assertThat(block.contains("::1")).isFalse()
    }

    @Test
    fun `ipv6 network matches and does not mix families`() {
        val block = CidrBlock.parse("fd00::/8")

        assertThat(block.contains("fd12:3456::1")).isTrue()
        assertThat(block.contains("[fd12:3456::1]")).isTrue()
        assertThat(block.contains("fe80::1")).isFalse()
        assertThat(block.contains("172.18.0.3")).isFalse()
    }

    @Test
    fun `ipv4 mapped ipv6 socket address matches an ipv4 network`() {
        val block = CidrBlock.parse("172.16.0.0/12")

        assertThat(block.contains("::ffff:172.18.0.3")).isTrue()
        assertThat(block.contains("::ffff:203.0.113.10")).isFalse()
    }

    @Test
    fun `null, blank and hostnames never match and are never resolved`() {
        val block = CidrBlock.parse("0.0.0.0/0")

        assertThat(block.contains(null)).isFalse()
        assertThat(block.contains("")).isFalse()
        assertThat(block.contains("localhost")).isFalse()
        assertThat(block.contains("example.com")).isFalse()
    }

    @Test
    fun `malformed specs are rejected`() {
        listOf("", "not-an-ip", "172.16.0.0/33", "172.16.0.0/-1", "172.16.0.0/abc", "fd00::/129", "example.com/24", "999.1.1.1/8")
            .forEach { spec ->
                assertThatThrownBy { CidrBlock.parse(spec) }
                    .describedAs(spec)
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `parseAll trims and ignores blank entries`() {
        val blocks = CidrBlock.parseAll(listOf(" 10.0.0.0/8 ", "", "  ", "172.16.0.0/12"))

        assertThat(blocks.map { it.toString() }).containsExactly("10.0.0.0/8", "172.16.0.0/12")
    }
}
