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
package com.google.devtools.build.lib.rules.java

import com.google.common.truth.Truth
import com.google.devtools.build.lib.rules.java.JavaInfo
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi.compileTimeJars
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi.fullCompileTimeJars
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi.sourceJars
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi.transitiveCompileTimeJars
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaInfoApi.transitiveRuntimeJars
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [JavaInfo].  */
@RunWith(JUnit4::class)
class JavaInfoTest {
    @get:org.junit.Test
    val transitiveRuntimeJars_noJavaCompilationArgsProvider: Unit
        get() {
            assertThat(JavaInfo.Companion.EMPTY_JAVA_INFO_FOR_TESTING.transitiveRuntimeJars.isEmpty()).isTrue()
        }

    @get:org.junit.Test
    val transitiveCompileTimeJarsJars_noJavaCompilationArgsProvider: Unit
        get() {
            assertThat(JavaInfo.Companion.EMPTY_JAVA_INFO_FOR_TESTING.transitiveCompileTimeJars.isEmpty())
                .isTrue()
        }

    @get:org.junit.Test
    val compileTimeJarsJars_noJavaCompilationArgsProvider: Unit
        get() {
            assertThat(JavaInfo.Companion.EMPTY_JAVA_INFO_FOR_TESTING.compileTimeJars.isEmpty()).isTrue()
        }

    @get:org.junit.Test
    val fullCompileTimeJarsJars_noJavaCompilationArgsProvider: Unit
        get() {
            assertThat(JavaInfo.Companion.EMPTY_JAVA_INFO_FOR_TESTING.fullCompileTimeJars.isEmpty()).isTrue()
        }

    @get:org.junit.Test
    val sourceJars_noJavaSourceJarsProvider: Unit
        get() {
            Truth.assertThat(JavaInfo.Companion.EMPTY_JAVA_INFO_FOR_TESTING.sourceJars).isEmpty()
        }
}
