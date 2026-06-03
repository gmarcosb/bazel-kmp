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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ActionInput

/** Tests for [RemotePathResolver]  */
@RunWith(JUnit4::class)
class RemotePathResolverTest {
    private var execRoot: Path? = null
    private var spawnExecutionContext: SpawnExecutionContext? = null
    private var input: ActionInput? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        execRoot = fs.getPath("/execroot/main")

        input = ActionInputHelper.fromPath("foo")
        spawnExecutionContext = Mockito.mock<SpawnExecutionContext>(SpawnExecutionContext::class.java)
        Mockito.`when`<T?>(
            spawnExecutionContext.getInputMapping(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenAnswer(
                Answer { invocationOnMock: InvocationOnMock? ->
                    val baseDirectory: PathFragment = invocationOnMock.getArgument<PathFragment>(0)
                    val inputMap: TreeMap<PathFragment?, ActionInput?> = TreeMap<PathFragment?, ActionInput?>()
                    inputMap.put(baseDirectory.getRelative(input.getExecPath()), input)
                    inputMap
                })
    }

    @get:org.junit.Test
    val workingDirectory_default_isInputRoot: Unit
        get() {
            val remotePathResolver: RemotePathResolver = RemotePathResolver.createDefault(execRoot)

            val workingDirectory: String? = remotePathResolver.getWorkingDirectory().getPathString()

            Truth.assertThat(workingDirectory).isEqualTo("")
        }

    @get:org.junit.Test
    val workingDirectory_sibling_isExecRootBaseName: Unit
        get() {
            val remotePathResolver: RemotePathResolver = SiblingRepositoryLayoutResolver(execRoot)

            val workingDirectory: String? = remotePathResolver.getWorkingDirectory().getPathString()

            Truth.assertThat(workingDirectory).isEqualTo("main")
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val inputMapping_default_inputsRelativeToExecRoot: Unit
        get() {
            val remotePathResolver: RemotePathResolver = RemotePathResolver.createDefault(execRoot)

            val inputs: SortedMap<PathFragment?, ActionInput?>? =
                remotePathResolver.getInputMapping(spawnExecutionContext, false)

            Truth.assertThat(inputs).containsExactly(PathFragment.create("foo"), input)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val inputMapping_sibling_inputsRelativeToInputRoot: Unit
        get() {
            val remotePathResolver: RemotePathResolver = SiblingRepositoryLayoutResolver(execRoot)

            val inputs: SortedMap<PathFragment?, ActionInput?>? =
                remotePathResolver.getInputMapping(spawnExecutionContext, false)

            Truth.assertThat(inputs).containsExactly(PathFragment.create("main/foo"), input)
        }

    @org.junit.Test
    fun convertPaths_default_relativeToWorkingDirectory() {
        val remotePathResolver: RemotePathResolver = RemotePathResolver.createDefault(execRoot)

        val outputPath: String? = remotePathResolver.localPathToOutputPath(PathFragment.create("bar"))
        val localPath: Path? = remotePathResolver.outputPathToLocalPath(outputPath)

        Truth.assertThat(outputPath).isEqualTo("bar")
        assertThat(localPath).isEqualTo(execRoot.getRelative("bar"))
    }

    @org.junit.Test
    fun convertPaths_siblingCompatible_relativeToWorkingDirectory() {
        val remotePathResolver: RemotePathResolver = SiblingRepositoryLayoutResolver(execRoot)

        val outputPath: String? = remotePathResolver.localPathToOutputPath(PathFragment.create("bar"))
        val localPath: Path? = remotePathResolver.outputPathToLocalPath(outputPath)

        Truth.assertThat(outputPath).isEqualTo("bar")
        assertThat(localPath).isEqualTo(execRoot.getRelative("bar"))
    }
}
