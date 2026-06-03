// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.util.ClassNameTest
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [ClassNameTest].  */
@RunWith(JUnit4::class)
class ClassNameTest {
    @org.junit.Test
    fun outerClassName() {
        assertThat(ClassName.getSimpleNameWithOuter(ClassNameTest::class.java)).isEqualTo("ClassNameTest")
    }

    internal class InnerClass

    @org.junit.Test
    fun innerClassName() {
        assertThat(ClassName.getSimpleNameWithOuter(com.google.devtools.build.lib.util.ClassNameTest.InnerClass::class.java))
            .isEqualTo("ClassNameTest\$InnerClass")
    }
}
