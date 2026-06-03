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
//
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.google.testing.junit.testparameterinjector.TestParameters
import com.google.testing.junit.testparameterinjector.TestParameters.TestParametersValues
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.nio.file.Path
import java.util.stream.Collectors

/** Unit tests for PathTransformingDelegateFileSystem. Make sure all methods rewrite paths.  */
@RunWith(TestParameterInjector::class)
class PathTransformingDelegateFileSystemTest {
    private val delegateFileSystem: FileSystem = createMockFileSystem()
    private val fileSystem = TestDelegateFileSystem(delegateFileSystem)

    @Before
    fun verifyGetDigestFunctionCalled() {
        // getDigestFunction gets called in the constructor of PathTransformingDelegateFileSystem, make
        // sure to "consume" that so that tests don't need to account for that.
        Mockito.verify<Any?>(delegateFileSystem, Mockito.atLeastOnce()).getDigestFunction()
        Mockito.verifyNoMoreInteractions(delegateFileSystem)
    }

    @org.junit.Test
    @TestParameters(valuesProvider = FileSystemMethodProvider::class)
    @Throws(java.lang.Exception::class)
    fun simplePathMethod_callsDelegateWithRewrittenPath(method: java.lang.reflect.Method) {
        val path: PathFragment = PathFragment.create("/original/dir/file")

        method.invoke(fileSystem, *pathAndDefaultArgs(method, path))

        method.invoke(
            Mockito.verify<Any?>(delegateFileSystem),
            *pathAndDefaultArgs(method, PathFragment.create("/transformed/dir/file"))
        )
        Mockito.verifyNoMoreInteractions(delegateFileSystem)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun readSymbolicLink_callsDelegateWithRewrittenPathAndTransformsItBack() {
        val path: PathFragment? = PathFragment.create("/original/dir/file")
        Mockito.`when`<T?>(delegateFileSystem.readSymbolicLink(PathFragment.create("/transformed/dir/file")))
            .thenReturn(PathFragment.create("/transformed/resolved"))

        val resolvedPath: PathFragment? = fileSystem.readSymbolicLink(path)

        assertThat(resolvedPath).isEqualTo(PathFragment.create("/original/resolved"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolveSymbolicLinks_callsDelegateWithRewrittenPathAndTransformsItBack() {
        val path: PathFragment? = PathFragment.create("/original/dir/file")
        Mockito.`when`<T?>(delegateFileSystem.resolveSymbolicLinks(PathFragment.create("/transformed/dir/file")))
            .thenReturn(Path.create("/transformed/resolved", delegateFileSystem))

        val resolvedPath: Path = fileSystem.resolveSymbolicLinks(path)

        assertThat(resolvedPath.asFragment()).isEqualTo(PathFragment.create("/original/resolved"))
        assertThat(resolvedPath.getFileSystem()).isSameInstanceAs(fileSystem)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSymbolicLink_callsDelegateWithRewrittenPathNotTarget() {
        val target: PathFragment? = PathFragment.create("/original/target")

        fileSystem.createSymbolicLink(
            PathFragment.create("/original/dir/file"), target, SymlinkTargetType.UNSPECIFIED
        )

        Mockito.verify<Any?>(delegateFileSystem)
            .createSymbolicLink(
                PathFragment.create("/transformed/dir/file"), target, SymlinkTargetType.UNSPECIFIED
            )
        Mockito.verifyNoMoreInteractions(delegateFileSystem)
    }

    private class TestDelegateFileSystem(fileSystem: FileSystem?) : PathTransformingDelegateFileSystem(fileSystem) {
        protected override fun toDelegatePath(path: PathFragment): PathFragment {
            return TRANSFORMED.getRelative(path.relativeTo(ORIGINAL))
        }

        protected override fun fromDelegatePath(delegatePath: PathFragment): PathFragment {
            return ORIGINAL.getRelative(delegatePath.relativeTo(TRANSFORMED))
        }

        companion object {
            private val ORIGINAL: PathFragment = PathFragment.create("/original")
            private val TRANSFORMED: PathFragment = PathFragment.create("/transformed")
        }
    }

    private class FileSystemMethodProvider :
        com.google.testing.junit.testparameterinjector.TestParametersValuesProvider() {
        public override fun provideValues(context: com.google.testing.junit.testparameterinjector.TestParametersValuesProvider.Context?): com.google.common.collect.ImmutableList<TestParametersValues?> {
            return java.util.Arrays.stream<java.lang.reflect.Method?>(FileSystem::class.java.getDeclaredMethods())
                .filter { m: java.lang.reflect.Method? ->
                    !IGNORED.contains(m) && !java.lang.reflect.Modifier.isStatic(m.getModifiers()) && !java.lang.reflect.Modifier.isFinal(
                        m.getModifiers()
                    ) && com.google.common.collect.ImmutableList.copyOf<java.lang.Class<*>?>(m.getParameterTypes())
                        .contains(PathFragment::class.java)
                }
                .map<TestParametersValues?> { m: java.lang.reflect.Method? ->
                    TestParametersValues.builder()
                        .name(m.getName() + parameterString(m.getParameterTypes()))
                        .addParameter("method", m)
                        .build()
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<TestParametersValues?>())
        }

        companion object {
            private val IGNORED: com.google.common.collect.ImmutableSet<java.lang.reflect.Method?> =
                com.google.common.collect.ImmutableSet.of<java.lang.reflect.Method?>(
                    getFileSystemMethod("getPath", PathFragment::class.java),
                    getFileSystemMethod("readSymbolicLink", PathFragment::class.java),
                    getFileSystemMethod("resolveSymbolicLinks", PathFragment::class.java),
                    getFileSystemMethod(
                        "createSymbolicLink",
                        PathFragment::class.java,
                        PathFragment::class.java,
                        SymlinkTargetType::class.java
                    )
                )

            private fun getFileSystemMethod(
                name: String,
                vararg parameterTypes: java.lang.Class<*>?
            ): java.lang.reflect.Method? {
                try {
                    return FileSystem::class.java.getDeclaredMethod(name, *parameterTypes)
                } catch (e: java.lang.NoSuchMethodException) {
                    throw java.lang.IllegalArgumentException(e)
                }
            }

            private fun parameterString(types: Array<java.lang.Class<*>?>): String? {
                return java.util.Arrays.stream<java.lang.Class<*>?>(types)
                    .map<String?> { obj: java.lang.Class<*>? -> obj.getSimpleName() }
                    .collect(Collectors.joining(", ", "(", ")"))
            }
        }
    }

    companion object {
        private fun createMockFileSystem(): FileSystem {
            val fileSystem: FileSystem = Mockito.mock<FileSystem>(FileSystem::class.java)
            Mockito.`when`<T?>(fileSystem.getDigestFunction()).thenReturn(DigestHashFunction.SHA256)
            Mockito.`when`<T?>(fileSystem.getPath(ArgumentMatchers.any<T?>(PathFragment::class.java)))
                .thenCallRealMethod()
            return fileSystem
        }

        private val DEFAULT_VALUES: com.google.common.collect.ImmutableClassToInstanceMap<*> =
            com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                .put<Boolean?>(Boolean::class.javaPrimitiveType, false)
                .put<Int?>(Int::class.javaPrimitiveType, 0)
                .put<Long?>(Long::class.javaPrimitiveType, 0L)
                .put<String?>(String::class.java, "")
                .build()

        private fun pathAndDefaultArgs(method: java.lang.reflect.Method, path: PathFragment): Array<Any?> {
            val types: Array<java.lang.Class<*>?> = method.getParameterTypes()
            val result = arrayOfNulls<Any>(types.size)
            for (i in types.indices) {
                if (types[i] == PathFragment::class.java) {
                    result[i] = path.replaceName(path.getBaseName() + i)
                    continue
                }
                result[i] =
                    com.google.common.base.Preconditions.checkNotNull(
                        DEFAULT_VALUES.get(types[i]), "Missing default value for: %s", types[i].getName()
                    )
            }
            return result
        }
    }
}
