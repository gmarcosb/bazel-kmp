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
package com.google.devtools.build.lib.analysis.starlark.annotations.processor

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.starlark.annotations.processor.StarlarkConfigurationFieldProcessor
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourceSubjectFactory
import com.google.testing.compile.JavaSourcesSubject.SingleSourceAdapter
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import javax.tools.JavaFileObject

/**
 * Unit tests for StarlarkConfigurationFieldProcessor.
 */
@RunWith(JUnit4::class)
class StarlarkConfigurationFieldProcessorTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGoldenConfigurationField() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("GoldenConfigurationField.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGoldenConfigurationFieldThroughApi() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("GoldenConfigurationFieldThroughApi.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHasMethodParameters() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("HasMethodParameters.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .failsToCompile()
            .withErrorContaining(
                "@StarlarkConfigurationField annotated methods must have zero arguments."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMethodIsPrivate() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("MethodIsPrivate.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .failsToCompile()
            .withErrorContaining("@StarlarkConfigurationField annotated methods must be public.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMethodThrowsException() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("MethodThrowsException.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .failsToCompile()
            .withErrorContaining("@StarlarkConfigurationField annotated must not throw exceptions.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonConfigurationFragment() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonConfigurationFragment.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .failsToCompile()
            .withErrorContaining(
                "@StarlarkConfigurationField annotated methods must be methods "
                        + "of configuration fragments."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExposedConfigurationFragment() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonExposedConfigurationFragment.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReturnsOtherType() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ReturnsOtherType.java"))
            .processedWith(StarlarkConfigurationFieldProcessor())
            .failsToCompile()
            .withErrorContaining("@StarlarkConfigurationField annotated methods must return Label.")
    }

    companion object {
        private fun getFile(pathToFile: String?): JavaFileObject {
            return JavaFileObjects.forResource(
                com.google.common.io.Resources.getResource(
                    StarlarkConfigurationFieldProcessorTest::class.java, "optiontestsources/" + pathToFile
                )
            )
        }
    }
}
