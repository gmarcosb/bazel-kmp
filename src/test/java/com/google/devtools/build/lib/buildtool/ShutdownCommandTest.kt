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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.runtime.BlazeCommandResult

/** Tests [ShutdownCommand].  */
@RunWith(JUnit4::class)
class ShutdownCommandTest {
    private val shutdown: ShutdownCommand = ShutdownCommand()
    private val optionsParser: OptionsParser =
        OptionsParser.builder().optionsClasses(ShutdownCommand.Options::class.java).build()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShutdownShutsDownWithStatusZero() {
        optionsParser.parse()
        val result: BlazeCommandResult = shutdown.exec(null, optionsParser)
        assertThat(result.getExitCode()).isEqualTo(ExitCode.SUCCESS)
        assertThat(result.shutdown()).isTrue()
    }
}
