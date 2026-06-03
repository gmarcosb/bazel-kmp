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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** Tests [JavaOptions].  */
@RunWith(JUnit4::class)
class JavaOptionsTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hostJavacOptions() {
        val options: BuildOptions = targetConfig.getOptions().clone()
        options.get(JavaOptions::class.java).setJavacOpts(com.google.common.collect.ImmutableList.of<E?>("-XDtarget"))
        options.get(JavaOptions::class.java).setHostJavacOpts(com.google.common.collect.ImmutableList.of<E?>("-XDhost"))

        val execOptions: BuildOptions = AnalysisTestUtil.execOptions(options, skyframeExecutor, reporter)
        com.google.common.truth.Subject.contains("-XDhost")
        com.google.common.truth.Subject.contains("-XDhost")
    }
}
