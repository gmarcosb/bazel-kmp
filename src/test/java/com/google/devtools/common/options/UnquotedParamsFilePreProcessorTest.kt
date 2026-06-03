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
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import com.google.devtools.common.options.OptionsParser.ArgAndFallbackData
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.ParamsFilePreProcessor
import com.google.devtools.common.options.UnquotedParamsFilePreProcessor
import net.starlark.java.syntax.Location.file
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Tests [UnquotedParamsFilePreProcessor].
 */
@RunWith(JUnit4::class)
class UnquotedParamsFilePreProcessorTest {
    private var paramsFile: Path? = null
    private var paramsFilePreProcessor: ParamsFilePreProcessor? = null

    @Before
    fun setup() {
        val fileSystem: java.nio.file.FileSystem = Jimfs.newFileSystem()
        paramsFile = fileSystem.getPath("paramsFile")
        paramsFilePreProcessor = UnquotedParamsFilePreProcessor(fileSystem)
    }

    @org.junit.Test
    @Throws(IOException::class, OptionsParsingException::class)
    fun testNewlines() {
        java.nio.file.Files.write(
            paramsFile,
            com.google.common.collect.ImmutableList.of<String?>("arg1\narg2\rarg3\r\narg4 arg5\targ6\n\rarg7\\ arg8"),
            java.nio.charset.StandardCharsets.UTF_8,
            StandardOpenOption.CREATE
        )
        val args =
            preProcess(paramsFilePreProcessor, com.google.common.collect.ImmutableList.of<String?>("@" + paramsFile))
        Truth.assertThat(args)
            .containsExactly("arg1", "arg2", "arg3", "arg4 arg5\targ6", "", "arg7\\ arg8")
            .inOrder()
    }

    companion object {
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
