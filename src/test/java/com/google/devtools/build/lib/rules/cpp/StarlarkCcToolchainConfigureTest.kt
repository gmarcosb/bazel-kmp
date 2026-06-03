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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.ActionExecutionContextBuilder.build
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase
import com.google.devtools.build.lib.testutil.TestConstants
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.StarlarkList
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/** Tests for cc autoconfiguration.  */
@RunWith(JUnit4::class)
class StarlarkCcToolchainConfigureTest {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSplitEscaped() {
        val mu: Mutability? = null
        newTest()
            .testExpression("split_escaped('a:b:c', ':')", StarlarkList.of<String?>(mu, "a", "b", "c"))
            .testExpression("split_escaped('a%:b', ':')", StarlarkList.of<String?>(mu, "a:b"))
            .testExpression("split_escaped('a%%b', ':')", StarlarkList.of<String?>(mu, "a%b"))
            .testExpression("split_escaped('a:::b', ':')", StarlarkList.of<String?>(mu, "a", "", "", "b"))
            .testExpression("split_escaped('a:b%:c', ':')", StarlarkList.of<String?>(mu, "a", "b:c"))
            .testExpression("split_escaped('a%%:b:c', ':')", StarlarkList.of<String?>(mu, "a%", "b", "c"))
            .testExpression("split_escaped(':a', ':')", StarlarkList.of<String?>(mu, "", "a"))
            .testExpression("split_escaped('a:', ':')", StarlarkList.of<String?>(mu, "a", ""))
            .testExpression("split_escaped('::a::', ':')", StarlarkList.of<String?>(mu, "", "", "a", "", ""))
            .testExpression("split_escaped('%%%:a%%%%:b', ':')", StarlarkList.of<String?>(mu, "%:a%%", "b"))
            .testExpression("split_escaped('', ':')", StarlarkList.of<Any?>(mu))
            .testExpression("split_escaped('%', ':')", StarlarkList.of<String?>(mu, "%"))
            .testExpression("split_escaped('%%', ':')", StarlarkList.of<String?>(mu, "%"))
            .testExpression("split_escaped('%:', ':')", StarlarkList.of<String?>(mu, ":"))
            .testExpression("split_escaped(':', ':')", StarlarkList.of<String?>(mu, "", ""))
            .testExpression("split_escaped('a%%b', ':')", StarlarkList.of<String?>(mu, "a%b"))
            .testExpression("split_escaped('a%:', ':')", StarlarkList.of<String?>(mu, "a:"))
    }

    @Throws(IOException::class)
    private fun newTest(vararg starlarkOptions: String?): com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase.Scenario {
        return ev.Scenario(*starlarkOptions)
            .setUp(
                com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                    TestConstants.RULES_CC_REPOSITORY_EXECROOT
                            + "cc/private/toolchain/lib_cc_configure.bzl"
                )
            )
    }
}
