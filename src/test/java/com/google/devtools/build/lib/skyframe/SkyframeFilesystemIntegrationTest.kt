// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.BuildFailedException

/** Skyframe integration tests where the [SyscallCache] throws [IOException]s.  */
@RunWith(TestParameterInjector::class)
class SkyframeFilesystemIntegrationTest : SkyframeIntegrationTestBase() {
    private val syscallCache: SyscallCache = spy(SyscallCache.NO_CACHE)

    override fun additionalEventsToCollect(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?>? {
        return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>(com.google.devtools.build.lib.events.EventKind.FINISH)
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() {
            val delegatingSyscallCache: DelegatingSyscallCache = DelegatingSyscallCache()
            delegatingSyscallCache.setDelegate(syscallCache)
            return super.runtimeBuilder
                .addBlazeModule(
                    object : BlazeModule() {
                        public override fun workspaceInit(
                            runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
                        ) {
                            builder.setSyscallCache(delegatingSyscallCache)
                        }
                    })
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noChangeSourceFile_dirtinessCheckerIoException_noActionExecution() {
        write(
            "hello/BUILD",
            "genrule(name='target', srcs = ['one'], outs=['out1'], cmd='/bin/cat $(SRCS) > $@')"
        )
        val pathToThrow: Path = write("hello/one", "original lines")

        buildTarget("//hello:target")

        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //hello:target")

        // Throw an IOException during the initial filesystem access of the source file dep for
        // configured target //hello:target.
        initializeFileSystemSingletonIoException(pathToThrow)

        buildTarget("//hello:target")

        Mockito.verify<Any?>(syscallCache, Mockito.atLeastOnce()).statIfFound(pathToThrow, Symlinks.NOFOLLOW)
        // Configured target //hello:target was dirtied during SkyValue dirtiness checking of
        // FileStateValue hello/one due to the thrown IOException. During the execution phase,
        // //hello:target is marked clean and not executed because hello/one is re-computed to the same
        // value with a subsequent successful filesystem access.
        MoreAsserts.assertDoesNotContainEvent(events.collector(), "Executing genrule //hello:target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changeSourceFile_dirtinessCheckerIoException_actionExecution() {
        write(
            "hello/BUILD",
            "genrule(name='target', srcs = ['one'], outs=['out1'], cmd='/bin/cat $(SRCS) > $@')"
        )
        write("hello/one", "original lines")

        buildTarget("//hello:target")

        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //hello:target")

        // Throw an IOException during the initial filesystem access of the source file dep for
        // configured target //hello:target.
        val pathToThrow: Path = write("hello/one", "new lines")
        initializeFileSystemSingletonIoException(pathToThrow)

        buildTarget("//hello:target")

        Mockito.verify<Any?>(syscallCache, Mockito.atLeastOnce()).statIfFound(pathToThrow, Symlinks.NOFOLLOW)
        // Configured target //hello:target was dirtied during SkyValue dirtiness checking of
        // FileStateValue hello/one due to the thrown IOException. During the execution phase,
        // //hello:target is re-executed because hello/one re-computed to a different value with a
        // subsequent successful filesystem access.
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //hello:target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun persistentIoException_inputFileError() {
        write(
            "hello/BUILD",
            "genrule(name='target', srcs = ['one'], outs=['out1'], cmd='/bin/cat $(SRCS) > $@')"
        )
        val pathToThrow: Path = write("hello/one", "original lines")

        buildTarget("//hello:target")

        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //hello:target")

        initializeFileSystemPersistentIoException(pathToThrow)

        val e: BuildFailedException =
            org.junit.Assert.assertThrows<T>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//hello:target") })

        Mockito.verify<Any?>(syscallCache, Mockito.atLeastOnce()).statIfFound(pathToThrow, Symlinks.NOFOLLOW)
        assertThat(e.getDetailedExitCode().getFailureDetail().getExecution().getCode())
            .isEqualTo(Code.SOURCE_INPUT_IO_EXCEPTION)
        com.google.common.truth.Subject.contains("error reading file '//hello:one'")
    }

    @Throws(IOException::class)
    private fun initializeFileSystemSingletonIoException(pathToThrow: Path?) {
        Mockito.`when`<T?>(syscallCache.statIfFound(pathToThrow, Symlinks.NOFOLLOW))
            .thenThrow(IOException("filesystem error"))
            .thenReturn(SyscallCache.NO_CACHE.statIfFound(pathToThrow, Symlinks.NOFOLLOW))
    }

    @Throws(IOException::class)
    private fun initializeFileSystemPersistentIoException(pathToThrow: Path?) {
        Mockito.`when`<T?>(syscallCache.statIfFound(pathToThrow, Symlinks.NOFOLLOW))
            .thenThrow(IOException("filesystem error"))
    }
}
