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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Artifact

/**
 * Integration test for action invalidation based on output modifications returned by [ ][OutputService.startBuild].
 */
@RunWith(TestParameterInjector::class)
class OutputsInvalidationIntegrationTest : BuildIntegrationTestCase() {
    private val outputService: OutputService = Mockito.mock<OutputService>(OutputService::class.java)

    @Before
    @Throws(BuildFailedException::class, AbruptExitException::class, java.lang.InterruptedException::class)
    fun prepareOutputServiceMock() {
        Mockito.`when`<T?>(outputService.actionFileSystemType()).thenReturn(ActionFileSystemType.DISABLED)
        Mockito.`when`<T?>(outputService.getFileSystemName(ArgumentMatchers.any<T?>())).thenReturn("fileSystemName")
        Mockito.`when`<T?>(
            outputService.startBuild(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenReturn(ModifiedFileSet.EVERYTHING_MODIFIED)
        Mockito.`when`<T?>(outputService.getXattrProvider(ArgumentMatchers.any<T?>()))
            .thenAnswer(Answer { i: InvocationOnMock? -> i.getArgument<Any?>(0) })
        Mockito.`when`<T?>(outputService.getRewoundActionSynchronizer())
            .thenReturn(OutputService.RewoundActionSynchronizer.NOOP)
    }

    @Throws(java.lang.Exception::class)
    override fun getRuntimeBuilder(): BlazeRuntime.Builder {
        return super.runtimeBuilder
            .addBlazeModule(
                object : BlazeModule() {
                    public override fun getOutputService(): OutputService {
                        return outputService
                    }
                })
    }

    override fun additionalEventsToCollect(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?> {
        return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>(com.google.devtools.build.lib.events.EventKind.FINISH)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nothingModified_doesntInvalidateAnyActions(@TestParameter deleteOutput: Boolean) {
        write("foo/BUILD", "genrule(name='foo', outs=['foo.out'], cmd='touch $@')")
        buildTarget("//foo")
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")
        if (deleteOutput) {
            delete(getOnlyOutput("//foo"))
        }

        Mockito.`when`<T?>(
            outputService.startBuild(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenReturn(ModifiedFileSet.NOTHING_MODIFIED)
        events.collector().clear()
        buildTarget("//foo")

        MoreAsserts.assertDoesNotContainEvent(events.collector(), "Executing genrule //foo:foo")
    }

    private enum class ReportedModification {
        EVERYTHING_MODIFIED {
            override fun modifiedFileSet(artifact: Artifact?): ModifiedFileSet {
                return ModifiedFileSet.EVERYTHING_MODIFIED
            }
        },
        EVERYTHING_DELETED {
            override fun modifiedFileSet(artifact: Artifact?): ModifiedFileSet {
                return ModifiedFileSet.EVERYTHING_DELETED
            }
        },
        SINGLE_FILE {
            override fun modifiedFileSet(artifact: Artifact): ModifiedFileSet {
                return ModifiedFileSet.builder().modify(artifact.getExecPath()).build()
            }
        };

        abstract fun modifiedFileSet(artifact: Artifact?): ModifiedFileSet?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun identicalOutputs_doesntInvalidateAnyActions(
        @TestParameter modification: ReportedModification
    ) {
        write("foo/BUILD", "genrule(name='foo', outs=['foo.out'], cmd='touch $@')")
        buildTarget("//foo")
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")

        Mockito.`when`<T?>(
            outputService.startBuild(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenReturn(modification.modifiedFileSet(getOnlyOutput("//foo")))
        events.collector().clear()
        buildTarget("//foo")

        MoreAsserts.assertDoesNotContainEvent(events.collector(), "Executing genrule //foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCheckOutputFiles_ignoresModifiedFiles(
        @TestParameter modification: ReportedModification
    ) {
        addOptions("--experimental_check_output_files")
        write("foo/BUILD", "genrule(name='foo', outs=['foo.out'], cmd='touch $@')")
        buildTarget("//foo")
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")

        Mockito.`when`<T?>(
            outputService.startBuild(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenReturn(modification.modifiedFileSet(getOnlyOutput("//foo")))
        events.collector().clear()
        buildTarget("//foo")

        MoreAsserts.assertDoesNotContainEvent(events.collector(), "Executing genrule //foo:foo")
    }

    @TestParameters(
        "{everythingDeleted: false, checkOutputFiles: true}",
        "{everythingDeleted: true, checkOutputFiles: false}",
        "{everythingDeleted: true, checkOutputFiles: true}"
    )
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun everythingModified_invalidatesAllActions(
        everythingDeleted: Boolean, checkOutputFiles: Boolean
    ) {
        addOptions("--experimental_check_output_files=" + checkOutputFiles)
        write("foo/BUILD", "genrule(name='foo', outs=['foo.out'], cmd='touch $@')")
        buildTarget("//foo")
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")
        delete(getOnlyOutput("//foo"))

        Mockito.`when`<T?>(
            outputService.startBuild(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenReturn(
                if (everythingDeleted)
                    ModifiedFileSet.EVERYTHING_DELETED
                else
                    ModifiedFileSet.EVERYTHING_MODIFIED
            )
        events.collector().clear()
        buildTarget("//foo")

        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFileModified_invalidatesOnlyAffectedAction() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            outs = ["foo.out"],
            cmd = "touch ${'$'}@",
        )

        genrule(
            name = "bar",
            outs = ["bar.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        buildTarget("//foo:all")
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")
        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:bar")
        val fooOut: Artifact = getOnlyOutput("//foo")
        delete(fooOut)

        Mockito.`when`<T?>(
            outputService.startBuild(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean()
            )
        )
            .thenReturn(modifiedFileSet(fooOut))
        events.collector().clear()
        buildTarget("//foo:all")

        MoreAsserts.assertContainsEvent(events.collector(), "Executing genrule //foo:foo")
        MoreAsserts.assertDoesNotContainEvent(events.collector(), "Executing genrule //foo:bar")
    }

    @Throws(java.lang.Exception::class)
    private fun getOnlyOutput(label: String?): Artifact {
        return getConfiguredTarget(label)
            .getProvider(FileProvider::class.java)
            .getFilesToBuild()
            .getSingleton()
    }

    companion object {
        @Throws(IOException::class)
        private fun delete(artifact: Artifact) {
            assertThat(artifact.getPath().delete()).isTrue()
        }

        private fun modifiedFileSet(vararg artifacts: Artifact): ModifiedFileSet {
            val modifiedFileSet: ModifiedFileSet.Builder = ModifiedFileSet.builder()
            for (artifact in artifacts) {
                modifiedFileSet.modify(artifact.getExecPath())
            }
            return modifiedFileSet.build()
        }
    }
}
