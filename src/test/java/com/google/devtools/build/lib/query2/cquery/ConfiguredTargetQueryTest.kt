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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** Tests for [ConfiguredTargetQueryEnvironment].  */
@RunWith(JUnit4::class)
abstract class ConfiguredTargetQueryTest : PostAnalysisQueryTest<CqueryNode>() {
    override fun createQueryHelper(): QueryHelper<CqueryNode?> {
        return ConfiguredTargetQueryHelper()
    }

    val defaultFunctions: HashMap<String?, QueryFunction?>
        get() {
            val defaultFunctions: com.google.common.collect.ImmutableList<QueryFunction> =
                com.google.common.collect.ImmutableList.copyOf(ConfiguredTargetQueryEnvironment.FUNCTIONS)
            val functions: HashMap<String?, QueryFunction?> = HashMap<String?, QueryFunction?>()
            for (queryFunction in defaultFunctions) {
                functions.put(queryFunction.name, queryFunction)
            }
            return functions
        }

    override fun getConfiguration(kct: CqueryNode): BuildConfigurationValue {
        return getHelper()
            .getSkyframeExecutor()
            .getConfiguration(getHelper().getReporter(), kct.getConfigurationKey())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    override fun testMultipleTopLevelConfigurations_nullConfigs() {
        writeFile(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "my_java",
            srcs = ["foo.java"],
        )
        
        """.trimIndent()
        )

        val result: MutableSet<CqueryNode>? = eval("//test:my_java+//test:foo.java")

        Truth.assertThat(result).hasSize(2)

        val resultIterator: MutableIterator<CqueryNode> = result!!.iterator()
        val first: CqueryNode = resultIterator.next()
        if (first.getLabel().toString().equals("//test:foo.java")) {
            assertThat(getConfiguration(first)).isNull()
            assertThat(getConfiguration(resultIterator.next())).isNotNull()
        } else {
            assertThat(getConfiguration(first)).isNotNull()
            assertThat(getConfiguration(resultIterator.next())).isNull()
        }
    }
}
