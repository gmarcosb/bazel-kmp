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
package com.google.devtools.common.options

import com.google.common.jimfs.Jimfs
import com.google.common.truth.Truth
import com.google.devtools.common.options.OptionsParser.ArgAndFallbackData
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.ParamsFilePreProcessor
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path

/**
 * Tests [ParamsFilePreProcessor].
 */
@RunWith(JUnit4::class)
class ParamsFilePreProcessorTest {
    private class MockParamsFilePreProcessor(fs: java.nio.file.FileSystem?) : ParamsFilePreProcessor(fs) {
        @Throws(IOException::class, OptionsParsingException::class)
        override fun parse(paramsFile: Path?): MutableList<String?> {
            return PARAM_FILE_ARGS
        }
    }

    private var fileSystem: java.nio.file.FileSystem? = null
    private var paramsFilePreProcessor: ParamsFilePreProcessor? = null

    @Before
    fun setup() {
        fileSystem = Jimfs.newFileSystem()
        paramsFilePreProcessor = MockParamsFilePreProcessor(fileSystem)
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testNoArgs() {
        val args = preProcess(paramsFilePreProcessor, com.google.common.collect.ImmutableList.of<String?>())
        Truth.assertThat(args).isEmpty()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testNoParamsFile() {
        val rawArgs: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("--foo", "foo val")
        val args = preProcess(paramsFilePreProcessor, rawArgs)
        Truth.assertThat(args).containsExactlyElementsIn(rawArgs).inOrder()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testParamsFileNotFirst() {
        val rawArgs: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--foo", "foo val", "@paramsFile")
        val args = preProcess(paramsFilePreProcessor, rawArgs)
        Truth.assertThat(args).containsExactlyElementsIn(rawArgs).inOrder()
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testTooManyArgs() {
        val rawArgs: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("@paramsFile", "--foo", "foo val")
        val expected: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { preProcess(paramsFilePreProcessor, rawArgs) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                String.format(ParamsFilePreProcessor.TOO_MANY_ARGS_ERROR_MESSAGE_FORMAT, rawArgs)
            )
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testExceptionDuringParsing() {
        val exceptionParser: ParamsFilePreProcessor = object : ParamsFilePreProcessor(fileSystem) {
            @Throws(IOException::class, OptionsParsingException::class)
            override fun parse(paramsFile: Path?): MutableList<String?>? {
                throw IOException("Error parsing " + paramsFile)
            }
        }
        val paramsFileName = "paramsFile"
        val paramsFile: Path? = fileSystem.getPath(paramsFileName)
        val expected: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    preProcess(
                        exceptionParser,
                        com.google.common.collect.ImmutableList.of<String?>("@" + paramsFileName)
                    )
                })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                String.format(
                    ParamsFilePreProcessor.ERROR_MESSAGE_FORMAT,
                    paramsFile,
                    "Error parsing " + paramsFileName
                )
            )
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun testParamsFile() {
        val args =
            preProcess(paramsFilePreProcessor, com.google.common.collect.ImmutableList.of<String?>("@paramsFile"))
        Truth.assertThat(args).containsExactlyElementsIn(PARAM_FILE_ARGS).inOrder()
    }

    companion object {
        private val PARAM_FILE_ARGS: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("params", "file", "args")

        @Throws(OptionsParsingException::class)
        private fun preProcess(
            preProcessor: ParamsFilePreProcessor,
            args: MutableList<String?>?
        ): MutableList<String?> {
            return com.google.common.collect.Lists.transform<ArgAndFallbackData?, String?>(
                preProcessor.preProcess(ArgAndFallbackData.wrapWithFallbackData(args, null)),
                com.google.common.base.Function { argAndFallbackData: ArgAndFallbackData? -> argAndFallbackData.arg })
        }
    }
}
