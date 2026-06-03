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
package com.google.devtools.common.options.processor

import com.google.common.truth.Truth
import com.google.devtools.common.options.processor.OptionProcessor
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourceSubjectFactory
import com.google.testing.compile.JavaSourcesSubject.SingleSourceAdapter
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import javax.tools.JavaFileObject

/** Unit tests for the compile-time checks in [OptionProcessor].  */
@RunWith(JUnit4::class)
class OptionProcessorTest {
    @org.junit.Test
    fun fieldOptionsAreRejected() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("FieldOption.java"))
            .processedWith(OptionProcessor())
            .failsToCompile()
            .withErrorContaining("Field options not supported anymore. Use method options instead.")
    }

    companion object {
        private fun getFile(pathToFile: String?): JavaFileObject {
            return JavaFileObjects.forResource(
                com.google.common.io.Resources.getResource(
                    "com/google/devtools/common/options/processor/optiontestsources/" + pathToFile
                )
            )
        }
    }
}
