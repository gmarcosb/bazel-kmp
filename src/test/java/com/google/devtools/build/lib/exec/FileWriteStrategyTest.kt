// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.AbstractAction

/** Tests for [FileWriteStrategy].  */
@RunWith(TestParameterInjector::class)
class FileWriteStrategyTest {
    private val fileWriteStrategy: FileWriteStrategy = FileWriteStrategy()

    private val fileSystem: SpiedFileSystem = SpiedFileSystem.createInMemorySpy()
    private val scratch: Scratch = Scratch(fileSystem)
    private var execRoot: Path? = null
    private var outputRoot: ArtifactRoot? = null

    @Before
    @Throws(IOException::class)
    fun createOutputRoot() {
        execRoot = scratch.dir("/execroot")
        outputRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "bazel-out")
        outputRoot.getRoot().asPath().createDirectory()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writeOutputToFile_writesCorrectOutput(
        @TestParameter("", "hello", "hello there") content: String
    ) {
        val action: AbstractAction = createAction("file")

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fileWriteStrategy.writeOutputToFile(
                action,
                createActionExecutionContext(),
                { out -> out.write(content.toByteArray(java.nio.charset.StandardCharsets.UTF_8)) },  /* makeExecutable= */
                false,  /* isRemotable= */
                false
            )

        assertThat(
            FileSystemUtils.readContent(
                action.getPrimaryOutput().getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        )
            .isEqualTo(content)
    }

    private enum class FailureMode : DeterministicWriter {
        OPEN_FAILURE {
            @Throws(IOException::class)
            override fun setupFileSystem(fileSystem: SpiedFileSystem, outputPath: PathFragment?) {
                Mockito.`when`<T?>(fileSystem.getOutputStream(outputPath,  /* append= */false,  /* internal= */false))
                    .thenThrow(INJECTED_EXCEPTION)
            }
        },
        WRITE_FAILURE {
            @Throws(IOException::class)
            override fun writeTo(out: java.io.OutputStream?) {
                throw INJECTED_EXCEPTION
            }
        },
        CLOSE_FAILURE {
            @Throws(IOException::class)
            override fun setupFileSystem(fileSystem: SpiedFileSystem, outputPath: PathFragment?) {
                val outputStream: java.io.OutputStream? =
                    Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
                Mockito.doThrow(INJECTED_EXCEPTION).`when`<java.io.OutputStream?>(outputStream).close()
                Mockito.`when`<T?>(fileSystem.getOutputStream(outputPath,  /* append= */false,  /* internal= */false))
                    .thenReturn(outputStream)
            }
        };

        @Throws(IOException::class)
        open fun setupFileSystem(fileSystem: SpiedFileSystem?, outputPath: PathFragment?) {
        }

        @Throws(IOException::class)
        public override fun writeTo(out: java.io.OutputStream?) {
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writeOutputToFile_errorInWriter_returnsFailure(@TestParameter failureMode: FailureMode) {
        val action: AbstractAction = createAction("file")
        failureMode.setupFileSystem(fileSystem, action.getPrimaryOutput().getPath().asFragment())

        val e: ExecException? =
            org.junit.Assert.assertThrows<T?>(
                EnvironmentalExecException::class.java,
                org.junit.function.ThrowingRunnable {
                    fileWriteStrategy.writeOutputToFile(
                        action,
                        createActionExecutionContext(),
                        failureMode,  /* makeExecutable= */
                        false,  /* isRemotable= */
                        false
                    )
                })

        assertThat(e).hasCauseThat().isSameInstanceAs(INJECTED_EXCEPTION)
        val detailExitCode: DetailedExitCode = getDetailExitCode(e)
        assertThat(detailExitCode.getExitCode()).isEqualTo(ExitCode.LOCAL_ENVIRONMENTAL_ERROR)
        assertThat(detailExitCode.getFailureDetail().getExecution().getCode())
            .isEqualTo(Code.FILE_WRITE_IO_EXCEPTION)
    }

    private fun getDetailExitCode(e: ExecException?): DetailedExitCode {
        return ActionExecutionException.fromExecException(e, NullAction())
            .getDetailedExitCode()
    }

    private fun createActionExecutionContext(): ActionExecutionContext {
        return ActionsTestUtil.createContext(
            com.google.devtools.build.lib.actions.util.DummyExecutor(fileSystem, execRoot), NullEventHandler.INSTANCE
        )
    }

    private fun createAction(outputRelativePath: String?): AbstractAction {
        return NullAction(
            ActionsTestUtil.createArtifactWithRootRelativePath(
                outputRoot, PathFragment.create(outputRelativePath)
            )
        )
    }

    companion object {
        private val INJECTED_EXCEPTION: IOException = IOException("oh no!")
    }
}
