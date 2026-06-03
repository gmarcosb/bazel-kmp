// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.ActionExecutionContextBuilder.build
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import com.google.devtools.common.options.OptionsParser
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A regression test for [BlazeServerStartupOptions].  */
@RunWith(JUnit4::class)
class BlazeServerStartupOptionsTest {
    // A regression test to make sure that the output_base option is correctly parsed if no explicit
    // value is provided.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputBaseIsNullByDefault() {
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        parser.parse()
        val result: BlazeServerStartupOptions? = parser.getOptions<O?>(BlazeServerStartupOptions::class.java)
        assertThat(result.outputBase).isNull()
    }
}
