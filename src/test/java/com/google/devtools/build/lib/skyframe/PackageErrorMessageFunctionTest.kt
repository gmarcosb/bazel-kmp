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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Tests for [PackageErrorMessageFunction].  */
@RunWith(JUnit4::class)
class PackageErrorMessageFunctionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoErrorMessage() {
        scratch.file("a/BUILD")
        assertThat(getPackageErrorMessageValue( /*keepGoing=*/false).result)
            .isEqualTo(Result.NO_ERROR)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageWithErrors() {
        // Opt out of failing fast on an error event.
        reporter.removeHandler(failFastHandler)

        scratch.file("a/BUILD", "imaginary_macro(name = 'this macro is not defined')")

        assertThat(getPackageErrorMessageValue( /*keepGoing=*/false).result)
            .isEqualTo(Result.ERROR)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoSuchPackageException() {
        scratch.file("a/BUILD", "load('//a:does_not_exist.bzl', 'imaginary_macro')")

        val packageErrorMessageValue: PackageErrorMessageValue =
            getPackageErrorMessageValue( /*keepGoing=*/true)
        assertThat(packageErrorMessageValue.result).isEqualTo(Result.NO_SUCH_PACKAGE_EXCEPTION)
        assertThat(packageErrorMessageValue.noSuchPackageExceptionMessage)
            .isEqualTo("error loading package 'a': cannot load '//a:does_not_exist.bzl': no such file")
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getPackageErrorMessageValue(keepGoing: Boolean): PackageErrorMessageValue {
        val key: SkyKey = PackageErrorMessageValue.key(PackageIdentifier.createInMainRepo("a"))
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(keepGoing)
                .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .build()
        val result: EvaluationResult<SkyValue?> =
            skyframeExecutor.getEvaluator()
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        assertThat(result.hasError()).isFalse()
        val value: SkyValue? = result.get(key)
        assertThat(value).isInstanceOf(PackageErrorMessageValue::class.java)
        return value as PackageErrorMessageValue
    }
}
