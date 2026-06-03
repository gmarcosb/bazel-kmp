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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.BuildFailedException

/**
 * Tests for local diff awareness. A good place for general tests of Bazel's interactions with
 * "smart" filesystems, so that open-source changes don't break Google-internal features around
 * smart filesystems.
 */
@RunWith(JUnit4::class)
class LocalDiffAwarenessIntegrationTest : SkyframeIntegrationTestBase() {
    private val throwOnNextStatIfFound: MutableMap<PathFragment?, IOException?> = HashMap<PathFragment?, IOException?>()

    @Throws(java.lang.Exception::class)
    override fun getRuntimeBuilder(): BlazeRuntime.Builder {
        return super.runtimeBuilder
            .addBlazeModule(
                object : BlazeModule() {
                    public override fun workspaceInit(
                        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
                    ) {
                        builder.addDiffAwarenessFactory(
                            Factory(
                                com.google.common.collect.ImmutableList.of<E?>(), FsEventsNativeDepsServiceImpl()
                            )
                        )
                    }

                    public override fun getCommandOptions(commandName: String?): Iterable<java.lang.Class<out OptionsBase?>?> {
                        return com.google.common.collect.ImmutableList.of<E?>(LocalDiffAwareness.Options::class.java)
                    }
                })
    }

    @Throws(java.lang.Exception::class)
    public override fun createFileSystem(): FileSystem {
        return object : DelegateFileSystem(super.createFileSystem()) {
            @Throws(IOException::class)
            public override fun statIfFound(path: PathFragment?, followSymlinks: Boolean): FileStatus {
                val e: IOException? = throwOnNextStatIfFound.remove(path)
                if (e != null) {
                    throw e
                }
                return super.statIfFound(path, followSymlinks)
            }
        }
    }

    @Before
    fun addOptions() {
        addOptions("--watchfs", "--experimental_windows_watchfs")
    }

    @org.junit.After
    fun checkExceptionsThrown() {
        Truth.assertWithMessage("Injected exception(s) not thrown").that(throwOnNextStatIfFound).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changedFile_detectsChange() {
        write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')")
        buildTarget("//foo")
        assertContents("hello", "//foo")
        write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo there > $@')")

        buildTargetWithRetryUntilSeesChange("//foo", "foo/BUILD")

        assertContents("there", "//foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changedIgnoredFile_ignoresChange() {
        val notIgnoredFilePath = "foo/BUILD"
        val ignoredFilePath = "foo/ignored-dir/BUILD"

        write(".bazelignore", "foo/ignored-dir")

        write(ignoredFilePath, "")
        write(notIgnoredFilePath, "genrule(name='foo', outs=['out'], cmd='echo hello > $@')")
        buildTarget("//foo")
        assertContents("hello", "//foo")

        write(notIgnoredFilePath, "genrule(name='foo', outs=['out'], cmd='echo there > $@')")
        write(ignoredFilePath, "A = 1")

        val ignoredFileChanged: AtomicBoolean = AtomicBoolean()
        val notIgnoredFileChanged: AtomicBoolean = AtomicBoolean()
        runtimeWrapper.registerSubscriber(
            object : Any() {
                @com.google.common.eventbus.Subscribe
                fun onChangedFiles(changedFiles: ChangedFilesMessage) {
                    ignoredFileChanged.compareAndSet(
                        false, changedFiles.changedFiles().contains(PathFragment.create(ignoredFilePath))
                    )
                    notIgnoredFileChanged.compareAndSet(
                        false,
                        changedFiles.changedFiles().contains(PathFragment.create(notIgnoredFilePath))
                    )
                }
            })

        // Work around the inherent raciness of LocalDiffAwareness where the FS events are
        // delivered asynchronously and fast running test can trigger an incremental build
        // before the change is observed.
        for (attempt in 0..9) {
            buildTarget("//foo")
            if (notIgnoredFileChanged.get() && !ignoredFileChanged.get()) {
                assertContents("there", "//foo")
                return
            }
        }

        if (!notIgnoredFileChanged.get()) {
            org.junit.Assert.fail("Didn't observe file change within allowed number of retries")
        }
        if (ignoredFileChanged.get()) {
            org.junit.Assert.fail("Observed ignored file change")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changedFile_statFails_errorDeferredUntilBuild() {
        write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo hello > $@')")
        buildTarget("//foo")
        assertContents("hello", "//foo")
        val buildFile: Path = write("foo/BUILD", "genrule(name='foo', outs=['out'], cmd='echo there > $@')")
        val injectedException: IOException = IOException("oh no!")
        throwOnNextStatIfFound.put(buildFile.asFragment(), injectedException)

        val e: T? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { buildTargetWithRetryUntilSeesChange("//foo", "foo/BUILD") })

        assertThat(e).hasMessageThat().contains(injectedException.message)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameDirectory_thenRenameBackWithRemovedFile_glob() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = glob(["*dir*/**"]),
            outs = ["out"],
            cmd = "echo ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        val dir: Path = write("foo/dir/file1.txt").getParentDirectory()
        write("foo/dir/file2.txt")
        buildTarget("//foo")
        assertContents("foo/dir/file1.txt foo/dir/file2.txt", "//foo")

        val newDir: Path = dir.getParentDirectory().getChild("new_dir")
        dir.renameTo(newDir)
        buildTargetWithRetryUntilSeesChange("//foo", "foo/dir/file1.txt")
        assertContents("foo/new_dir/file1.txt foo/new_dir/file2.txt", "//foo")

        newDir.getChild("file2.txt").delete()
        newDir.renameTo(dir)
        buildTargetWithRetryUntilSeesChange("//foo", "foo/new_dir/file1.txt")
        assertContents("foo/dir/file1.txt", "//foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameDirectory_thenRenameBackWithRemovedFile_inputs() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["dir/file1.txt", "dir/file2.txt"],
            outs = ["out"],
            cmd = "echo ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        val dir: Path = write("foo/dir/file1.txt").getParentDirectory()
        write("foo/dir/file2.txt")
        buildTarget("//foo")
        assertContents("foo/dir/file1.txt foo/dir/file2.txt", "//foo")

        val newDir: Path = dir.getParentDirectory().getChild("new_dir")
        dir.renameTo(newDir)
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["new_dir/file1.txt", "new_dir/file2.txt"],
            outs = ["out"],
            cmd = "echo ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        buildTargetWithRetryUntilSeesChange("//foo", "foo/dir/file1.txt")
        assertContents("foo/new_dir/file1.txt foo/new_dir/file2.txt", "//foo")

        newDir.getChild("file2.txt").delete()
        newDir.renameTo(dir)
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["dir/file1.txt"],
            outs = ["out"],
            cmd = "echo ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        buildTargetWithRetryUntilSeesChange("//foo", "foo/new_dir/file1.txt")
        assertContents("foo/dir/file1.txt", "//foo")

        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["dir/file2.txt"],
            outs = ["out"],
            cmd = "echo ${'$'}(SRCS) > ${'$'}@",
        )
        
        """.trimIndent()
        )
        val e: T? = org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo") })
        // It is important that the error message says "do not exist", not "are in error". The latter
        // would indicate that the corresponding FileStateValue still considers the file to exist.
        assertThat(e).hasMessageThat().contains("1 input file(s) do not exist")
    }

    /**
     * Runs [.buildTarget] repeatedly until we observe a change for the given path.
     * 
     * 
     * This allows to work around the inherent raciness of `LocalDiffAwareness` where the FS
     * events are delivered asynchronously and fast running test can trigger an incremental build
     * before the change is observed.
     */
    @Throws(java.lang.Exception::class)
    private fun buildTargetWithRetryUntilSeesChange(target: String?, path: String?) {
        val changed: AtomicBoolean = AtomicBoolean()
        runtimeWrapper.registerSubscriber(
            object : Any() {
                @com.google.common.eventbus.Subscribe
                fun onChangedFiles(changedFiles: ChangedFilesMessage) {
                    changed.compareAndSet(
                        false, changedFiles.changedFiles().contains(PathFragment.create(path))
                    )
                }
            })
        for (attempt in 0..9) {
            buildTarget(target)
            if (changed.get()) {
                return
            }
        }
        org.junit.Assert.fail("Didn't observe file change within allowed number of retries")
    }

    // This test doesn't use --watchfs functionality, but if the source filesystem doesn't offer diffs
    // Bazel must scan the full Skyframe graph anyway, so a bug in checking output files wouldn't be
    // detected without --watchfs.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ignoreOutputFilesThenCheckAgainDoesCheck() {
        if ("bazel" == this.runtime.productName) {
            // Repository options only in Bazel.
            addOptions("--noexperimental_check_external_repository_files")
        }
        val buildFile: Path =
            write(
                "foo/BUILD",
                "genrule(name = 'foo', outs = ['out'], cmd = 'cp $< $@', srcs = ['link'])"
            )
        val outputFile: Path = directories.getOutputBase().getChild("linkTarget")
        FileSystemUtils.writeContentAsLatin1(outputFile, "one")
        buildFile.getParentDirectory().getChild("link").createSymbolicLink(outputFile.asFragment())

        buildTarget("//foo:foo")

        assertContents("one", "//foo:foo")

        addOptions("--noexperimental_check_output_files")
        FileSystemUtils.writeContentAsLatin1(outputFile, "two")

        buildTarget("//foo:foo")

        assertContents("one", "//foo:foo")

        addOptions("--experimental_check_output_files")

        buildTarget("//foo:foo")

        assertContents("two", "//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun externalSymlink_doesNotTriggerFullGraphTraversal() {
        addOptions("--symlink_prefix=/")
        if ("bazel" == this.runtime.productName) {
            // Repository options only in Bazel.
            addOptions("--noexperimental_check_external_repository_files")
        }
        val calledGetValues: AtomicInteger = AtomicInteger(0)
        skyframeExecutor()
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    com.google.devtools.build.skyframe.NotifyingHelper.Listener { key: SkyKey?, type: com.google.devtools.build.skyframe.NotifyingHelper.EventType?, order: com.google.devtools.build.skyframe.NotifyingHelper.Order?, context: Any? ->
                        if (type == NotifyingHelper.EventType.GET_VALUES) {
                            calledGetValues.incrementAndGet()
                        }
                    })
            )
        write(
            "hello/BUILD",
            "genrule(name='target', srcs = ['external'], outs=['out'], cmd='/bin/cat $(SRCS) > $@')"
        )
        val externalLink = java.lang.System.getenv("TEST_TMPDIR") + "/target"
        write(externalLink, "one")
        createSymlink(externalLink, "hello/external")

        // Trivial build: external symlink is not seen, so normal diff awareness is in play.
        buildTarget("//hello:BUILD")
        // New package path on first build triggers full-graph work.
        calledGetValues.set(0)

        // getValuesAndExceptions() called during output file checking (although if an output service is
        // able to report modified files in practice there is no iteration).
        buildTarget("//hello:BUILD")
        Truth.assertThat(calledGetValues.getAndSet(0)).isEqualTo(1)

        // Now bring the external symlink into Bazel's awareness.
        buildTarget("//hello:target")
        assertContents("one", "//hello:target")
        Truth.assertThat(calledGetValues.getAndSet(0)).isEqualTo(1)

        // Builds that follow a build containing an external file don't trigger a traversal.
        buildTarget("//hello:target")
        assertContents("one", "//hello:target")
        Truth.assertThat(calledGetValues.getAndSet(0)).isEqualTo(1)

        write(externalLink, "two")

        buildTarget("//hello:target")
        // External file changes are tracked.
        assertContents("two", "//hello:target")
        Truth.assertThat(calledGetValues.getAndSet(0)).isEqualTo(1)
    }
}
