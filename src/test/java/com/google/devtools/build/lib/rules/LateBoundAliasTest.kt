// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules

import com.google.devtools.build.lib.analysis.AliasProvider

/**
 * Tests that LateBoundAlias can resolve null actual reference.
 */
@RunWith(JUnit4::class)
class LateBoundAliasTest : BuildViewTestCase() {
    /** Test fragment.  */
    class TestFragment(buildOptions: BuildOptions?) : Fragment()

    private class TestLateBoundDefault : LabelLateBoundDefault<TestFragment?>(
        com.google.devtools.build.lib.rules.LateBoundAliasTest.TestFragment::class.java,
        { rule -> null },
        null
    ) {
        public override fun resolve(rule: Rule?, attributes: AttributeMap?, input: TestFragment?): Label? {
            return null
        }
    }

    private class MyTestRule : CommonAliasRule<TestFragment?>(
        "test_rule_name",
        { env -> TestLateBoundDefault() },
        com.google.devtools.build.lib.rules.LateBoundAliasTest.TestFragment::class.java
    )

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(com.google.devtools.build.lib.rules.LateBoundAliasTest.TestFragment::class.java)
        builder.addRuleDefinition(MyTestRule())
        return builder.build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveNullTarget() {
        scratch.file("a/BUILD", "test_rule_name(name='alias')")

        val alias: ConfiguredTarget = getConfiguredTarget("//a:alias")

        assertThat(alias).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNullTargetCanBeDependant() {
        scratch.file(
            "a/BUILD",
            """
        test_rule_name(name = "alias")

        filegroup(
            name = "my_filegroup",
            srcs = [":alias"],
        )
        
        """.trimIndent()
        )

        val myFilegroup: ConfiguredTarget = getConfiguredTarget("//a:my_filegroup")

        assertThat(myFilegroup).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNullTargetHasLateBoundAliasProvider() {
        scratch.file("a/BUILD", "test_rule_name(name='alias')")

        val alias: ConfiguredTarget = getConfiguredTarget("//a:alias")

        assertThat(alias).isNotNull()
        assertThat(alias.getProvider(AliasProvider.LateBoundAliasProvider::class.java)).isNotNull()
    }
}
