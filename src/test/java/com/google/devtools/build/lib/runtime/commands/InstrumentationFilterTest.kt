// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.buildtool.InstrumentationFilterSupport

/** Test for --instrumentation_filter heuristic in the [CoverageCommand] class.  */
@RunWith(JUnit4::class)
class InstrumentationFilterTest : BuildViewTestCase() {
    private var events: EventCollector? = null

    @Before
    fun removeFastFailHandler() {
        reporter.removeHandler(failFastHandler)
    }

    @Before
    fun initializeEvents() {
        events = EventCollector(com.google.devtools.build.lib.events.EventKind.INFO)
    }

    @Throws(java.lang.Exception::class)
    private fun getTargets(vararg labels: String?): MutableList<Target?> {
        val targets: MutableList<Target?> = java.util.ArrayList<Target?>()
        for (label in labels) {
            targets.add(getTarget(label))
        }
        return targets
    }

    private fun assertEventsReportInstrumentationFilter(expectedFilter: String?) {
        val messages: MutableList<String?> = java.util.ArrayList<String?>()
        for (event in events) {
            messages.add(event.getMessage())
        }
        Truth.assertThat(messages)
            .containsExactly(
                String.format(
                    "Using default value for --instrumentation_filter: \"%s\".", expectedFilter
                ),
                "Override the above default with --instrumentation_filter"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleTest() {
        scratch.file(
            "my/package1/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        val targets = getTargets("//my/package1:t1")
        val expectedFilter = "^//my/package1[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllTestsInPackage() {
        scratch.file(
            "foo/test/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "t1",
            srcs = ["t1.sh"],
        )

        foo_test(
            name = "t2",
            srcs = ["t1.sh"],
        )
        
        """.trimIndent()
        )
        val targets = getTargets("//foo/test:t1", "//foo/test:t2")
        val expectedFilter = "^//foo/test[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplePackages() {
        scratch.file(
            "my/package1/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "t1",
            srcs = ["t1.sh"],
        )

        test_suite(
            name = "ts",
            tests = [
                "//other/package1:t1",
                "//other/package2:ts",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "other/package1/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        scratch.file("other/package2/BUILD", "test_suite(name='ts', tests=['//other/package3:t3'])")
        scratch.file(
            "other/package3/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t3',srcs=['t3.sh'])"
        )
        val targets = getTargets("//my/package1:t1", "//other/package1:t1")
        val expectedFilter = "^//my/package1[/:],^//other/package1[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuiteExpansion() {
        scratch.file(
            "my/package1/BUILD",
            "test_suite(name='ts', tests=['//other/package1:t1', '//other/package2:ts'])"
        )
        scratch.file(
            "other/package1/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        scratch.file("other/package2/BUILD", "test_suite(name='ts', tests=['//other/package3:t3'])")
        scratch.file(
            "other/package3/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t3',srcs=['t3.sh'])"
        )
        val targets = getTargets("//my/package1:ts")
        val expectedFilter = "^//my/package1[/:],^//other/package1[/:],^//other/package2[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParentAndChildPackageCombined() {
        scratch.file(
            "parent/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1', srcs=['t1.sh'])"
        )
        scratch.file(
            "parent/child/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t2', srcs=['t2.sh'])"
        )
        val targets = getTargets("//parent:t1", "//parent/child:t2")
        val expectedFilter = "^//parent[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavascriptTests() {
        scratch.file(
            "javascript/other/tests/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        val targets = getTargets("//javascript/other/tests:t1")
        val expectedFilter = "^//javascript/other[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavaTests() {
        scratch.file(
            "javatests/other/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        val targets = getTargets("//javatests/other:t1")
        val expectedFilter = "^//java/other[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInternal() {
        scratch.file(
            "another/internal/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        val targets = getTargets("//another/internal:t1")
        val expectedFilter = "^//another[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPublic() {
        scratch.file(
            "another/public/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        val targets = getTargets("//another/public:t1")
        val expectedFilter = "^//another[/:]"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludesTopLevel() {
        scratch.file(
            "BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        InstrumentationFilterSupport.computeInstrumentationFilter(
            events, getTargets("//:t1", "//foo:t1")
        )
        assertEventsReportInstrumentationFilter("^//")
        val targets = getTargets()
        val expectedFilter = "^//"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelOnly() {
        scratch.file(
            "BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='t1',srcs=['t1.sh'])"
        )
        val targets = getTargets("//:t1")
        val expectedFilter = "^//"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        assertEventsReportInstrumentationFilter(expectedFilter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoTests() {
        val targets = getTargets()
        val expectedFilter = "^//"
        assertThat(InstrumentationFilterSupport.computeInstrumentationFilter(events, targets))
            .isEqualTo(expectedFilter)
        // If there are no targets, this doesn't get output at all.
        Truth.assertThat(events).isEmpty()
    }
}
