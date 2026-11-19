package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

// ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

private fun tmpDir(): File = Files.createTempDirectory("agentos-xml-parser-test").toFile()

private fun File.writeXml(
    name: String,
    content: String,
): File = resolve(name).also { it.writeText(content) }

private fun allPassXml(tests: Int = 3) =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <testsuite name="com.example.MyTest" tests="$tests" skipped="0" failures="0" errors="0">
      <testcase classname="com.example.MyTest" name="testA" time="0.1"/>
      <testcase classname="com.example.MyTest" name="testB" time="0.2"/>
      <testcase classname="com.example.MyTest" name="testC" time="0.3"/>
    </testsuite>
    """.trimIndent()

private fun oneFailureXml() =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <testsuite name="com.example.MyTest" tests="2" skipped="0" failures="1" errors="0">
      <testcase classname="com.example.MyTest" name="shouldPass" time="0.1"/>
      <testcase classname="com.example.MyTest" name="shouldFail" time="0.2">
        <failure message="expected &lt;foo&gt; but was &lt;bar&gt;" type="AssertionError">
          at com.example.MyTest.shouldFail(MyTest.kt:42)
          at java.lang.reflect.Method.invoke(Method.java:498)
          at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
        </failure>
      </testcase>
    </testsuite>
    """.trimIndent()

private fun oneErrorXml() =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <testsuite name="com.example.ErrorTest" tests="1" skipped="0" failures="0" errors="1">
      <testcase classname="com.example.ErrorTest" name="shouldNotThrow" time="0.1">
        <error message="NullPointerException" type="java.lang.NullPointerException">
          at com.example.ErrorTest.shouldNotThrow(ErrorTest.kt:17)
        </error>
      </testcase>
    </testsuite>
    """.trimIndent()

private fun skippedXml() =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <testsuite name="com.example.SkipTest" tests="3" skipped="1" failures="0" errors="0">
      <testcase classname="com.example.SkipTest" name="testA" time="0.1"/>
      <testcase classname="com.example.SkipTest" name="testB" time="0.0">
        <skipped/>
      </testcase>
      <testcase classname="com.example.SkipTest" name="testC" time="0.1"/>
    </testsuite>
    """.trimIndent()

private fun malformedXml() = "this is not xml <<< broken"

private fun xmlAttr(value: String) =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "&#10;")

/** One failing testcase of `com.example.MyTest`: name, failure message (may be multi-line), raw stack text. */
private data class FailingCase(
    val name: String,
    val message: String,
    val stack: String,
)

private fun failuresXml(vararg cases: FailingCase) =
    buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<testsuite name="com.example.MyTest" tests="${cases.size}" skipped="0" failures="${cases.size}" errors="0">""")
        cases.forEach {
            appendLine("""<testcase classname="com.example.MyTest" name="${it.name}" time="0.1">""")
            appendLine("""<failure message="${xmlAttr(it.message)}" type="AssertionError">${it.stack}</failure>""")
            appendLine("</testcase>")
        }
        appendLine("</testsuite>")
    }

private fun summaryOf(vararg cases: FailingCase): String {
    val dir = tmpDir()
    try {
        val report = dir.writeXml("TEST-MyTest.xml", failuresXml(*cases))
        return XmlTestReportParser().buildSummary(1, listOf(report))
    } finally {
        dir.deleteRecursively()
    }
}

/** Tool-owned report location (see [TestReportsInitScript]): `<reportsDir>/<project>-<hash>/test`. */
private fun File.testResultsDir(project: String = "root-1a2b"): File = resolve("$project/test").also { it.mkdirs() }

// ── spec ──────────────────────────────────────────────────────────────────────────────────────────────────

class XmlTestReportParserUnitSpec :
    StringSpec({

        // ── collect ───────────────────────────────────────────────────────────────────────────

        "collect returns empty list when no XML files exist" {
            val dir = tmpDir()
            try {
                XmlTestReportParser().collect(dir) shouldBe emptyList()
            } finally {
                dir.deleteRecursively()
            }
        }

        "collect returns only TEST-*.xml files" {
            val dir = tmpDir()
            try {
                val results = dir.testResultsDir()
                results.writeXml("TEST-com.example.MyTest.xml", allPassXml())
                results.writeXml("IGNORED-com.example.MyTest.xml", allPassXml())
                results.writeXml("TEST-other.txt", "irrelevant")

                val result = XmlTestReportParser().collect(dir)

                result.map { it.name } shouldBe listOf("TEST-com.example.MyTest.xml")
            } finally {
                dir.deleteRecursively()
            }
        }

        "collect walks subdirectories recursively" {
            val dir = tmpDir()
            try {
                dir.testResultsDir().writeXml("TEST-com.example.MyTest.xml", allPassXml())

                val result = XmlTestReportParser().collect(dir)

                result.size shouldBe 1
            } finally {
                dir.deleteRecursively()
            }
        }

        "collect finds the reports of every project, included builds too" {
            val dir = tmpDir()
            try {
                dir.testResultsDir("engine-1111").writeXml("TEST-A.xml", allPassXml())
                dir.testResultsDir("lib-2222").writeXml("TEST-B.xml", allPassXml())

                XmlTestReportParser().collect(dir).map { it.name }.sorted() shouldBe listOf("TEST-A.xml", "TEST-B.xml")
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── delete ───────────────────────────────────────────────────────────────────────────────────────────

        "delete removes the whole reports dir" {
            val dir = tmpDir()
            try {
                dir.testResultsDir("engine-1111").writeXml("TEST-A.xml", allPassXml())
                dir.testResultsDir("cli-2222").writeXml("TEST-B.xml", allPassXml())

                XmlTestReportParser().delete(dir)

                dir.exists() shouldBe false
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── buildSummary — all pass ───────────────────────────────────────────────────────────────

        "buildSummary reports SUCCESS on exit code 0" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", allPassXml())
                val summary = XmlTestReportParser().buildSummary(0, listOf(report))

                summary shouldContain "SUCCESS (exit 0)"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary reports FAILURE on non-zero exit code" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", oneFailureXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "FAILURE (exit 1)"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary counts tests correctly for all-pass report" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", allPassXml(tests = 3))
                val summary = XmlTestReportParser().buildSummary(0, listOf(report))

                summary shouldContain "Tests: 3  Passed: 3  Skipped: 0  Failed: 0"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary counts skipped correctly" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-SkipTest.xml", skippedXml())
                val summary = XmlTestReportParser().buildSummary(0, listOf(report))

                summary shouldContain "Tests: 3  Passed: 2  Skipped: 1  Failed: 0"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary has no failure block when all pass" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", allPassXml())
                val summary = XmlTestReportParser().buildSummary(0, listOf(report))

                summary shouldNotContain "Failures"
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── buildSummary — failures ──────────────────────────────────────────────────────────────

        "buildSummary includes failure block for failing test" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", oneFailureXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "── Failures"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary reports correct class and method name for failure" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", oneFailureXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "MyTest › shouldFail"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary reports failure message" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", oneFailureXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "expected <foo> but was <bar>"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary reports first relevant stack line, skipping reflect frames" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-MyTest.xml", oneFailureXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "at com.example.MyTest.shouldFail(MyTest.kt:42)"
                summary shouldNotContain "java.lang.reflect"
                summary shouldNotContain "sun.reflect"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary counts error node as failure" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-ErrorTest.xml", oneErrorXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "Failed: 1"
                summary shouldContain "ErrorTest › shouldNotThrow"
                summary shouldContain "NullPointerException"
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── buildSummary — failure detail ─────────────────────────────────────────────────────

        "buildSummary keeps every line of a multi-line message, indented" {
            val summary = summaryOf(FailingCase("t", "expected:<foo>\nbut was:<bar>", "at com.example.MyTest.t(MyTest.kt:1)"))

            summary shouldContain "message : expected:<foo>\n             but was:<bar>"
        }

        "buildSummary keeps at most 5 project frames and drops framework noise" {
            val stack =
                """
                at org.junit.Assert.fail(Assert.java:1)
                at com.example.A.one(A.kt:1)
                at com.example.A.two(A.kt:2)
                at com.example.A.three(A.kt:3)
                at com.example.A.four(A.kt:4)
                at com.example.A.five(A.kt:5)
                at com.example.A.six(A.kt:6)
                at java.base/jdk.internal.reflect.X.invoke(X.java:1)
                at io.kotest.Foo.bar(Foo.kt:1)
                """.trimIndent()

            val summary = summaryOf(FailingCase("t", "boom", stack))

            summary shouldContain "A.one(A.kt:1)"
            summary shouldContain "A.five(A.kt:5)"
            summary shouldNotContain "A.six"
            summary shouldNotContain "org.junit"
            summary shouldNotContain "jdk.internal"
            summary shouldNotContain "io.kotest"
        }

        "buildSummary keeps each intermediate Caused by with its first project frame" {
            val stack =
                """
                at com.example.A.top(A.kt:1)
                Caused by: java.io.IOException: disk full
                at java.base/java.io.File.write(File.java:1)
                at com.example.Repo.save(Repo.kt:9)
                at com.example.Repo.other(Repo.kt:10)
                Caused by: java.lang.IllegalStateException: root
                at com.example.Root.x(Root.kt:1)
                """.trimIndent()

            val summary = summaryOf(FailingCase("t", "boom", stack))

            summary shouldContain "Caused by: java.io.IOException: disk full"
            summary shouldContain "at com.example.Repo.save(Repo.kt:9)"
            summary shouldNotContain "File.write"
            summary shouldNotContain "Repo.other"
        }

        "buildSummary keeps up to 5 project frames of the root cause" {
            val stack =
                """
                at com.example.A.top(A.kt:1)
                Caused by: java.io.IOException: wrapper
                at com.example.Wrap.w(Wrap.kt:1)
                at com.example.Wrap.w2(Wrap.kt:2)
                Caused by: java.lang.IllegalStateException: root
                at java.base/java.lang.Thread.run(Thread.java:1)
                at com.example.R.one(R.kt:1)
                at com.example.R.two(R.kt:2)
                at com.example.R.three(R.kt:3)
                at com.example.R.four(R.kt:4)
                at com.example.R.five(R.kt:5)
                at com.example.R.six(R.kt:6)
                """.trimIndent()

            val summary = summaryOf(FailingCase("t", "boom", stack))

            summary shouldContain "R.one(R.kt:1)"
            summary shouldContain "R.five(R.kt:5)"
            summary shouldNotContain "R.six"
            summary shouldNotContain "Thread.run"
            summary shouldNotContain "Wrap.w2"
        }

        "buildSummary reports the exception type of the failure" {
            val summary = summaryOf(FailingCase("t", "boom", "at com.example.A.a(A.kt:1)"))

            summary shouldContain "type    : AssertionError"
        }

        "buildSummary reports the exception type of an error node" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-ErrorTest.xml", oneErrorXml())

                XmlTestReportParser().buildSummary(1, listOf(report)) shouldContain "type    : java.lang.NullPointerException"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary falls back to the first raw frame when every frame is noise" {
            val stack = "at org.junit.Assert.fail(Assert.java:1)\nat org.junit.Other.x(Other.java:2)"

            val summary = summaryOf(FailingCase("t", "boom", stack))

            summary shouldContain "org.junit.Assert.fail"
            summary shouldNotContain "org.junit.Other"
        }

        "buildSummary truncates a huge message and says how much" {
            val summary = summaryOf(FailingCase("t", "x".repeat(7000), "at com.example.A.a(A.kt:1)"))

            summary shouldContain "… (1000 chars truncated)"
        }

        "buildSummary does not truncate a message that fits the budget" {
            val summary = summaryOf(FailingCase("t", "x".repeat(1000), "at com.example.A.a(A.kt:1)"))

            summary shouldNotContain "truncated"
        }

        "buildSummary shrinks the per-failure message cap when failures are many" {
            val cases =
                (1..20).map {
                    FailingCase("t$it", it.toString().padStart(3, '0') + "y".repeat(997), "at com.example.A.a(A.kt:1)")
                }

            val summary = summaryOf(*cases.toTypedArray())

            summary shouldContain "… (600 chars truncated)"
        }

        "buildSummary groups failures with the same message and head frame" {
            val stack = "at com.example.A.a(A.kt:1)"
            val summary =
                summaryOf(
                    FailingCase("a", "boom", stack),
                    FailingCase("b", "boom", stack),
                    FailingCase("c", "boom", stack),
                    FailingCase("d", "other", stack),
                )

            summary shouldContain "1. ×3 : MyTest › a, MyTest › b, MyTest › c"
            summary shouldContain "2. MyTest › d"
            summary.split("message : boom").size shouldBe 2
        }

        // ── buildSummary — multiple reports ─────────────────────────────────────────────────────────

        "buildSummary aggregates totals across multiple reports" {
            val dir = tmpDir()
            try {
                val r1 = dir.writeXml("TEST-A.xml", allPassXml(tests = 3))
                val r2 = dir.writeXml("TEST-B.xml", oneFailureXml())
                val summary = XmlTestReportParser().buildSummary(1, listOf(r1, r2))

                // 3 from allPassXml + 2 from oneFailureXml
                summary shouldContain "Tests: 5"
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── malformed XML ────────────────────────────────────────────────────────────────────────

        "buildSummary skips malformed XML and continues with valid reports" {
            val dir = tmpDir()
            try {
                val bad = dir.writeXml("TEST-Bad.xml", malformedXml())
                val good = dir.writeXml("TEST-Good.xml", allPassXml())
                val summary = XmlTestReportParser().buildSummary(0, listOf(bad, good))

                // valid report still counted
                summary shouldContain "Tests: 3"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary says how many reports could not be read" {
            val dir = tmpDir()
            try {
                val bad = dir.writeXml("TEST-Bad.xml", malformedXml())
                val good = dir.writeXml("TEST-Good.xml", allPassXml())

                XmlTestReportParser().buildSummary(0, listOf(bad, good)) shouldContain "1 report(s) could not be read"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary refuses a report with a DOCTYPE and never reads the file its external entity points to" {
            val dir = tmpDir()
            try {
                val secret = dir.writeXml("secret.txt", "TOP-SECRET-CONTENT")
                val evil =
                    dir.writeXml(
                        "TEST-Evil.xml",
                        """
                        <?xml version="1.0"?>
                        <!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
                        <testsuite name="&xxe;" tests="1" skipped="0" failures="1" errors="0">
                          <testcase classname="c" name="t"><failure message="&xxe;" type="X">&xxe;</failure></testcase>
                        </testsuite>
                        """.trimIndent(),
                    )

                val summary = XmlTestReportParser().buildSummary(1, listOf(evil))

                summary shouldContain "1 report(s) could not be read"
                summary shouldNotContain "TOP-SECRET-CONTENT"
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── buildSummary — streamed reading ────────────────────────────────────────────────────────────────────

        "buildSummary reads a report whose captured output is very large" {
            val dir = tmpDir()
            try {
                val report = dir.resolve("TEST-Huge.xml")
                report.bufferedWriter().use { out ->
                    out.write("""<testsuite name="c" tests="2" skipped="0" failures="1" errors="0">""")
                    out.write("""<testcase classname="c.C" name="ok"/>""")
                    out.write("""<testcase classname="c.C" name="ko"><failure message="boom" type="X">at c.C.ko(C.kt:1)</failure></testcase>""")
                    out.write("<system-out><![CDATA[")
                    // 100 MB of captured output: reading it must not depend on holding it in memory
                    val line = "x".repeat(1_000) + "\n"
                    repeat(100_000) { out.write(line) }
                    out.write("]]></system-out></testsuite>")
                }

                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "Tests: 2  Passed: 1  Skipped: 0  Failed: 1"
                summary shouldContain "C › ko"
                summary shouldContain "boom"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary does not mistake the captured output of a test case for its failure" {
            val dir = tmpDir()
            try {
                val report =
                    dir.writeXml(
                        "TEST-Out.xml",
                        """
                        <testsuite name="c" tests="1" skipped="0" failures="1" errors="0">
                          <testcase classname="c.C" name="ko">
                            <failure message="boom" type="X">at c.C.ko(C.kt:1)</failure>
                            <system-out>at com.printed.ByTheTest(Printed.kt:99)</system-out>
                          </testcase>
                        </testsuite>
                        """.trimIndent(),
                    )

                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "C.kt:1"
                summary shouldNotContain "Printed.kt"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary counts a report with no root element as unreadable" {
            val dir = tmpDir()
            try {
                val empty = dir.writeXml("TEST-Empty.xml", "")

                XmlTestReportParser().buildSummary(0, listOf(empty)) shouldContain "1 report(s) could not be read"
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── buildSummary — raw gradle output when the reports do not explain a failed build ────────────────────

        "buildSummary bounds the raw output and keeps its end" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-A.xml", allPassXml())
                val raw = "BEGINNING-OF-THE-LOG" + "> Task :a:test\n".repeat(5_000) + "What went wrong: THE-VERDICT"

                val summary = XmlTestReportParser().buildSummary(1, listOf(report), rawOutput = raw)

                summary shouldContain "THE-VERDICT"
                summary shouldContain "earlier chars omitted"
                summary shouldNotContain "BEGINNING-OF-THE-LOG"
                (summary.length < MAX_RAW_OUTPUT_CHARS + 500) shouldBe true
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary appends the raw output when the build failed and no test failed" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-A.xml", allPassXml())

                val summary = XmlTestReportParser().buildSummary(1, listOf(report), rawOutput = "No tests found for given includes")

                summary shouldContain "FAILURE (exit 1)"
                summary shouldContain "Tests: 3"
                summary shouldContain "Raw output"
                summary shouldContain "No tests found for given includes"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary appends the raw output when the build failed and a report could not be read" {
            val dir = tmpDir()
            try {
                val bad = dir.writeXml("TEST-Bad.xml", malformedXml())
                val failing = dir.writeXml("TEST-Fail.xml", oneFailureXml())

                val summary = XmlTestReportParser().buildSummary(1, listOf(bad, failing), rawOutput = "gradle said this")

                summary shouldContain "shouldFail"
                summary shouldContain "gradle said this"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary does not append the raw output when a failed test explains the failure" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-Fail.xml", oneFailureXml())

                val summary = XmlTestReportParser().buildSummary(1, listOf(report), rawOutput = "gradle noise")

                summary shouldContain "shouldFail"
                summary shouldNotContain "gradle noise"
                summary shouldNotContain "Raw output"
            } finally {
                dir.deleteRecursively()
            }
        }

        "buildSummary does not append the raw output when the build succeeded" {
            val dir = tmpDir()
            try {
                val report = dir.writeXml("TEST-A.xml", allPassXml())

                XmlTestReportParser().buildSummary(0, listOf(report), rawOutput = "gradle noise") shouldNotContain "gradle noise"
            } finally {
                dir.deleteRecursively()
            }
        }

        // ── buildSummary — bounded answer ──────────────────────────────────────────────────────────────────────

        "buildSummary of 200 distinct failures keeps the exact counts and a bounded list" {
            val dir = tmpDir()
            try {
                val testcases =
                    (1..200).joinToString("\n") { i ->
                        """<testcase classname="com.example.Big" name="test$i"><failure message="distinct message $i" type="AssertionError">at com.example.Big.test$i(Big.kt:$i)</failure></testcase>"""
                    }
                val report =
                    dir.writeXml(
                        "TEST-Big.xml",
                        """<?xml version="1.0"?><testsuite name="com.example.Big" tests="200" skipped="0" failures="200" errors="0">$testcases</testsuite>""",
                    )

                val summary = XmlTestReportParser().buildSummary(1, listOf(report))

                summary shouldContain "Tests: 200  Passed: 0  Skipped: 0  Failed: 200"
                summary shouldContain "distinct message 1"
                summary shouldContain "distinct message 50"
                summary shouldNotContain "distinct message 51"
                summary shouldContain "150 more distinct failure(s) not shown"
            } finally {
                dir.deleteRecursively()
            }
        }
    })
