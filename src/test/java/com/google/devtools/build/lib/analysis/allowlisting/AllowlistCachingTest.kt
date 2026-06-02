// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.allowlisting

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Tests that allowlists are invalidated after change.  */
@RunWith(JUnit4::class)
class AllowlistCachingTest : AnalysisCachingTestBase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun addDummyRule() {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addRuleDefinition(AllowlistDummyRule.DEFINITION)
        useRuleClassProvider(builder.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStillCorrectAfterChangesToAllowlist() {
        scratch.file("allowlist/BUILD", "package_group(name='allowlist', packages=[])")
        scratch.file("x/BUILD", "rule_with_allowlist(name='x')")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//x:x") })
        assertContainsEvent("Dummy is not available.")
        eventCollector.clear()
        reporter.addHandler(FoundationTestCase.failFastHandler)
        scratch.overwriteFile(
            "allowlist/BUILD",
            """
        package_group(
            name = "allowlist",
            packages = [
                "//...",
            ],
        )
        
        """.trimIndent()
        )
        update("//x:x")
        assertNoEvents()
    }
}
