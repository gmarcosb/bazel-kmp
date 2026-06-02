// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.collect.*
import com.google.common.io.CharStreams
import com.google.devtools.build.lib.actions.FileValue
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.syntax.FileOptions
import net.starlark.java.syntax.Location
import net.starlark.java.syntax.ParserInput
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.File
import java.io.InputStreamReader
import java.lang.String
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Exception
import kotlin.plus
import kotlin.toString

/** Unit tests for complex function of StarlarkRepositoryContext.  */
@RunWith(JUnit4::class)
class StarlarkRepositoryContextTest {
    private var scratch: Scratch? = null
    private var outputBase: Path? = null
    private var outputDirectory: Path? = null
    private var root: Root? = null
    private var context: StarlarkRepositoryContext? = null
    private var fakeFileLabel: Label? = null
    private var repoRule: RepoRule? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        scratch = Scratch("/")
        outputBase = scratch.dir("/outputBase")
        outputDirectory = scratch.dir("/outputDir")
        root = Root.fromPath(scratch.dir("/wsRoot"))
        scratch.file("/wsRoot/WORKSPACE")
        setUpRepoRule(false)
    }

    private fun setUpRepoRule(remotable: Boolean, vararg attributes: Attribute?) {
        val repoRuleBuilder: RepoRule.Builder = RepoRule.builder()
        for (attr in attributes) {
            repoRuleBuilder.addAttribute(attr)
        }
        repoRuleBuilder
            .impl(exec("def test(ctx): pass", "test") as StarlarkFunction?)
            .configure(false)
            .doc(Optional.empty<T?>())
            .environ(ImmutableSet.of<E?>())
            .local(false)
            .remotable(remotable)
            .recordedRepoMappingEntries(ImmutableTable.of<R?, C?, V?>())
            .transitiveBzlDigest(ByteString.EMPTY)
        repoRuleBuilder
            .idBuilder()
            .bzlFileLabel(Label.parseCanonicalUnchecked("//:test.bzl"))
            .ruleName("test")
        repoRule = repoRuleBuilder.build()
    }

    @Throws(Exception::class)
    private fun setUpRepo(
        kwargs: MutableMap<String?, Any?>,
        ignoredSubdirectories: IgnoredSubdirectories,
        repoEnvVariables: ImmutableMap<String?, String?>?,
        clientEnvVariables: ImmutableMap<String?, String?>?,
        starlarkSemantics: StarlarkSemantics?,
        repoRemoteExecutor: RepositoryRemoteExecutor?
    ) {
        val labelConverter: LabelConverter =
            LabelConverter(PackageIdentifier.EMPTY_PACKAGE_ID, RepositoryMapping.EMPTY)
        val listener: ExtendedEventHandler? = Mockito.mock<ExtendedEventHandler?>(ExtendedEventHandler::class.java)
        val repoSpec: RepoSpec =
            repoRule.instantiate(kwargs, DUMMY_STACK, labelConverter, listener, "somewhere")
        val repoDefinition: RepoDefinition =
            RepoDefinition(repoRule, repoSpec.attributes(), kwargs.get("name") as String?, null)
        val downloader: DownloadManager = Mockito.mock<DownloadManager>(DownloadManager::class.java)
        val environment: SkyFunction.Environment =
            Mockito.mock<SkyFunction.Environment>(SkyFunction.Environment::class.java)
        Mockito.`when`<T?>(environment.getListener()).thenReturn(listener)
        fakeFileLabel = Label.parseCanonical("//:foo")
        Mockito.`when`<T?>(environment.getValue(PackageLookupValue.key(fakeFileLabel.getPackageIdentifier())))
            .thenReturn(PackageLookupValue.success(root, BuildFileName.BUILD))
        Mockito.`when`<T?>(environment.getValueOrThrow(ArgumentMatchers.any<T?>(), < T > eq < T ? > (IOException::class.java)))
        .thenReturn(Mockito.< T > mock < T ? > (FileValue::class.java))
        val packageLocator: PathPackageLocator =
            PathPackageLocator(
                outputDirectory,
                ImmutableList.of<E?>(root),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(root.asPath(), outputBase, root.asPath()),
                root.asPath(),
                AnalysisMock.Companion.get().getProductName()
            )
        context =
            StarlarkRepositoryContext(
                repoDefinition,
                packageLocator,
                outputDirectory,
                ignoredSubdirectories,
                environment,
                repoEnvVariables,
                clientEnvVariables,
                downloader,
                1.0,  /* processWrapper= */
                null,
                starlarkSemantics,
                repoRemoteExecutor,
                SyscallCache.NO_CACHE,
                directories
            )
    }

    @Throws(Exception::class)
    private fun setUpRepo(name: String) {
        setUpRepo(name, StarlarkSemantics.DEFAULT)
    }

    @Throws(Exception::class)
    private fun setUpRepo(name: String, starlarkSemantics: StarlarkSemantics?) {
        setUpRepo(
            ImmutableMap.of<String?, Any?>("name", name),
            IgnoredSubdirectories.EMPTY,
            ImmutableMap.of<String?, String?>("FOO", "BAR"),
            ImmutableMap.of<String?, String?>("FOO", "BAR"),
            starlarkSemantics,  /* repoRemoteExecutor= */
            null
        )
    }

    @Test
    @Throws(Exception::class)
    fun testAttr() {
        setUpRepoRule( /* remotable= */false, Attribute.attr("foo", Type.STRING).build())
        setUpRepo(
            ImmutableMap.of<String?, Any?>("name", "test", "foo", "bar"),
            IgnoredSubdirectories.EMPTY,
            ImmutableMap.of<String?, String?>("FOO", "BAR"),
            ImmutableMap.of<String?, String?>("FOO", "BAR"),
            StarlarkSemantics.DEFAULT,  /* repoRemoteExecutor= */
            null
        )

        Truth.assertThat(context!!.attr.getFieldNames()).contains("foo")
        Truth.assertThat(context!!.attr.getValue("foo")).isEqualTo("bar")
    }

    @Test
    @Throws(Exception::class)
    fun testWhich() {
        setUpRepo(
            ImmutableMap.of<String?, Any?>("name", "test"),
            IgnoredSubdirectories.EMPTY,
            ImmutableMap.of<String?, String?>("PATH", String.join(File.pathSeparator, "/bin", "/path/sbin", ".")),
            ImmutableMap.of<kotlin.String?, kotlin.String?>(
                "PATH",
                String.join(File.pathSeparator, "/bin", "/path/sbin", ".")
            ),
            StarlarkSemantics.DEFAULT,  /* repoRemoteExecutor= */
            null
        )
        scratch.file("/bin/true").setExecutable(true)
        scratch.file("/path/sbin/true").setExecutable(true)
        scratch.file("/path/sbin/false").setExecutable(true)
        scratch.file("/path/bin/undef").setExecutable(true)
        scratch.file("/path/bin/def").setExecutable(true)
        scratch.file("/bin/undef")

        Truth.assertThat(context!!.which("anything", thread)).isNull()
        Truth.assertThat(context!!.which("def", thread)).isNull()
        Truth.assertThat(context!!.which("undef", thread)).isNull()
        Truth.assertThat(context!!.which("true", thread).toString()).isEqualTo("/bin/true")
        Truth.assertThat(context!!.which("false", thread).toString()).isEqualTo("/path/sbin/false")
    }

    @Test
    @Throws(Exception::class)
    fun testFile() {
        setUpRepo("test")
        context!!.createFile(context!!.getPath("foobar"), "", true, true, thread)
        context!!.createFile(context!!.getPath("foo/bar"), "foobar", true, true, thread)
        context!!.createFile(context!!.getPath("bar/foo/bar"), "", true, true, thread)

        testOutputFile(outputDirectory.getChild("foobar"), "")
        testOutputFile(outputDirectory.getRelative("foo/bar"), "foobar")
        testOutputFile(outputDirectory.getRelative("bar/foo/bar"), "")

        try {
            context!!.createFile(context!!.getPath("/absolute"), "", true, true, thread)
            Assert.fail("Expected error on creating path outside of the repository directory")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Cannot write outside of the repository directory for path /absolute")
        }
        try {
            context!!.createFile(context!!.getPath("../somepath"), "", true, true, thread)
            Assert.fail("Expected error on creating path outside of the repository directory")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Cannot write outside of the repository directory for path /somepath")
        }
        try {
            context!!.createFile(context!!.getPath("foo/../../somepath"), "", true, true, thread)
            Assert.fail("Expected error on creating path outside of the repository directory")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Cannot write outside of the repository directory for path /somepath")
        }
        val ex: RepositoryFunctionException? =
            Assert.assertThrows<T?>(
                RepositoryFunctionException::class.java,
                ThrowingRunnable {
                    context!!.createFile(
                        Starlark.str(context!!.getPath(""), StarlarkSemantics.DEFAULT) + "_1",
                        "",
                        true,
                        true,
                        thread
                    )
                })
        assertThat(ex)
            .hasCauseThat()
            .hasMessageThat()
            .isEqualTo("Cannot write outside of the repository directory for path /outputDir_1")
    }

    @Test
    @Throws(Exception::class)
    fun testDelete() {
        setUpRepo("testDelete")
        val bar: Path = outputDirectory.getRelative("foo/bar")
        val path1: Any = bar.getPathString()
        val barPath = context!!.getPath(path1)
        context!!.createFile(barPath, "content", true, true, thread)
        Truth.assertThat(context!!.delete(barPath, thread)).isTrue()

        Truth.assertThat(context!!.delete(barPath, thread)).isFalse()

        val tempFile: Path = scratch.file("/abcde/b", "123")
        val path: Any = tempFile.getPathString()
        Truth.assertThat(context!!.delete(context!!.getPath(path), thread)).isTrue()

        val innerDir: Path = scratch.dir("/some/inner")
        scratch.dir("/some/inner/deeper")
        scratch.file("/some/inner/deeper.txt")
        scratch.file("/some/inner/deeper/1.txt")
        Truth.assertThat(context!!.delete(innerDir.toString(), thread)).isTrue()

        val underWorkspace: Path = root.getRelative("under_workspace")
        try {
            context!!.delete(underWorkspace.toString(), thread)
            Assert.fail()
        } catch (expected: EvalException) {
            Truth.assertThat(expected.message)
                .startsWith("delete() can only be applied to external paths")
        }

        scratch.file(underWorkspace.getPathString(), "123")
        setUpRepo(
            ImmutableMap.of<kotlin.String?, Any?>("name", "test"),
            IgnoredSubdirectories.of(ImmutableSet.of<E?>(PathFragment.create("under_workspace"))),
            ImmutableMap.of<kotlin.String?, kotlin.String?>("FOO", "BAR"),
            ImmutableMap.of<kotlin.String?, kotlin.String?>("FOO", "BAR"),
            StarlarkSemantics.DEFAULT,  /* repoRemoteExecutor= */
            null
        )
        Truth.assertThat(context!!.delete(underWorkspace.toString(), thread)).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testRead() {
        setUpRepo("test")
        context!!.createFile(context!!.getPath("foo/bar"), "foobar", true, true, thread)

        val content = context!!.readFile(context!!.getPath("foo/bar"), "auto", thread)
        Truth.assertThat(content).isEqualTo("foobar")
    }

    @Test
    @Throws(Exception::class)
    fun testPatch() {
        setUpRepo("test")
        val foo = context!!.getPath("foo")
        context!!.createFile(foo, "line one\n", false, true, thread)
        val patchFile = context!!.getPath("my.patch")
        context!!.createFile(
            context!!.getPath("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, thread
        )
        context!!.patch(patchFile, StarlarkInt.of(0), "auto", thread)
        testOutputFile(foo.path, "line one\nline two\n")
    }

    @Test
    @Throws(Exception::class)
    fun testCannotFindFileToPatch() {
        setUpRepo("test")
        val patchFile = context!!.getPath("my.patch")
        context!!.createFile(
            context!!.getPath("my.patch"), "--- foo\n+++ foo\n" + ONE_LINE_PATCH, false, true, thread
        )
        try {
            context!!.patch(patchFile, StarlarkInt.of(0), "auto", thread)
            Assert.fail("Expected RepositoryFunctionException")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo(
                    "Error applying patch /outputDir/my.patch: Cannot find file to patch (near line 1)"
                            + ", old file name (foo) doesn't exist, new file name (foo) doesn't exist."
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun testPatchOutsideOfExternalRepository() {
        setUpRepo("test")
        val patchFile = context!!.getPath("my.patch")
        context!!.createFile(
            context!!.getPath("my.patch"),
            "--- ../other_root/foo\n" + "+++ ../other_root/foo\n" + ONE_LINE_PATCH,
            false,
            true,
            thread
        )
        try {
            context!!.patch(patchFile, StarlarkInt.of(0), "auto", thread)
            Assert.fail("Expected RepositoryFunctionException")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo(
                    "Error applying patch /outputDir/my.patch: Cannot patch file outside of external "
                            + "repository (/outputDir), file path = \"../other_root/foo\" at line 1"
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun testPatchErrorWasThrown() {
        setUpRepo("test")
        val foo = context!!.getPath("foo")
        val patchFile = context!!.getPath("my.patch")
        context!!.createFile(foo, "line1\nline2\nWRONG\nALSO WRONG\nline5\nline6\n", false, true, thread)
        val patch: kotlin.String =
            """
        --- foo
        +++ foo
        @@ -1,6 +1,7 @@
         line1
         line2
         line3
         line4
        +inserted
         line5
         line6
        
        """.trimIndent()
        context!!.createFile(context!!.getPath("my.patch"), patch, false, true, thread)
        try {
            context!!.patch(patchFile, StarlarkInt.of(0), "auto", thread)
            Assert.fail("Expected RepositoryFunctionException")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo(
                    ("Error applying patch /outputDir/my.patch: in patch applied to "
                            + "/outputDir/foo: could not apply patch due to"
                            + " CONTENT_DOES_NOT_MATCH_TARGET, error applying change near line 1")
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun testRemoteExec() {
        // Test that context.execute() can call out to remote execution and correctly forward
        // execution properties.

        // Prepare mocked remote repository and corresponding repository rule.

        val attrValues =
            ImmutableMap.of<kotlin.String?, Any?>(
                "name",
                "configure",
                "exec_properties",
                Dict.builder<Any?, Any?>().put("OSFamily", "Linux").buildImmutable()
            )

        val repoRemoteExecutor: RepositoryRemoteExecutor =
            Mockito.mock<RepositoryRemoteExecutor>(RepositoryRemoteExecutor::class.java)
        val executionResult: ExecutionResult =
            ExecutionResult(
                0,
                "test-stdout".toByteArray(StandardCharsets.US_ASCII),
                "test-stderr".toByteArray(StandardCharsets.US_ASCII)
            )
        Mockito.`when`<T?>(
            repoRemoteExecutor.execute(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(executionResult)

        setUpRepoRule( /* remotable= */
            true, Attribute.attr("exec_properties", Types.STRING_DICT).build()
        )
        setUpRepo(
            attrValues,
            IgnoredSubdirectories.EMPTY,
            ImmutableMap.of<kotlin.String?, kotlin.String?>("FOO", "BAR"),
            ImmutableMap.of<kotlin.String?, kotlin.String?>("FOO", "BAR"),
            StarlarkSemantics.builder()
                .setBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC, true)
                .build(),
            repoRemoteExecutor
        )

        // Execute the `StarlarkRepositoryContext`.
        val starlarkExecutionResult =
            context!!.execute(
                StarlarkList.of<kotlin.String?>( /* mutability= */null, "/bin/cmd", "arg1"),  /* timeoutI= */
                StarlarkInt.of(10),  /* uncheckedEnvironment= */
                Dict.empty<Any?, Any?>(),  /* quiet= */
                true,  /* overrideWorkingDirectory= */
                "",
                thread
            )

        // Verify the remote repository rule was run and its response returned.
        Mockito.verify<Any?>(repoRemoteExecutor)
            .execute( /* arguments= */
                ImmutableList.of<E?>("/bin/cmd", "arg1"),  /* inputFiles= */
                ImmutableSortedMap.of<K?, V?>(),  /* executionProperties= */
                ImmutableMap.of<K?, V?>("OSFamily", "Linux"),  /* environment= */
                ImmutableMap.of<K?, V?>(),  /* workingDirectory= */
                "",  /* timeout= */
                Duration.ofSeconds(10)
            )
        Truth.assertThat(starlarkExecutionResult.returnCode).isEqualTo(0)
        Truth.assertThat(starlarkExecutionResult.stdout).isEqualTo("test-stdout")
        Truth.assertThat(starlarkExecutionResult.stderr).isEqualTo("test-stderr")
    }

    @Test
    @Throws(Exception::class)
    fun testRename() {
        setUpRepo("test")
        context!!.createFile(context!!.getPath("foo"), "foobar", true, true, thread)

        context!!.rename(context!!.getPath("foo"), context!!.getPath("bar/baz"), thread)
        testOutputFile(outputDirectory.getChild("bar").getChild("baz"), "foobar")

        try {
            context!!.rename(context!!.getPath("/foo"), context!!.getPath("bar"), thread)
            Assert.fail("Expected error on renaming path outside of the repository directory")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Cannot write outside of the repository directory for path /foo")
        }

        try {
            context!!.rename(context!!.getPath("foo"), context!!.getPath("/bar"), thread)
            Assert.fail("Expected error on renaming path outside of the repository directory")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Cannot write outside of the repository directory for path /bar")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testRenameConflict() {
        setUpRepo("test")
        context!!.createFile(context!!.getPath("foo"), "fooooo", true, true, thread)
        context!!.createFile(context!!.getPath("bar"), "baaaar", true, true, thread)
        context!!.createFile(context!!.getPath("baz.d/baz"), "baaaaz", true, true, thread)

        try {
            context!!.rename(context!!.getPath("foo"), context!!.getPath("bar"), thread)
            Assert.fail("Expected error on renaming to a path that already exists")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Could not rename /outputDir/foo to /outputDir/bar: already exists")
        }

        try {
            context!!.rename(context!!.getPath("foo"), context!!.getPath("baz.d"), thread)
            Assert.fail("Expected error on renaming to a path that already exists")
        } catch (ex: RepositoryFunctionException) {
            assertThat(ex)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("Could not rename /outputDir/foo to /outputDir/baz.d: already exists")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testSymlink() {
        setUpRepo("test")
        context!!.createFile(context!!.getPath("foo"), "foobar", true, true, thread)

        context!!.symlink(context!!.getPath("foo"), context!!.getPath("bar"), thread)
        testOutputFile(outputDirectory.getChild("bar"), "foobar")

        Truth.assertThat(context!!.getPath("bar").realpath()).isEqualTo(context!!.getPath("foo"))
    }

    @Test
    @Throws(Exception::class)
    fun testDirectoryListing() {
        setUpRepo("test")
        scratch.file("/my/folder/a")
        scratch.file("/my/folder/b")
        scratch.file("/my/folder/c")
        Truth.assertThat(context!!.getPath("/my/folder").readdir("no"))
            .containsExactly(
                context!!.getPath("/my/folder/a"),
                context!!.getPath("/my/folder/b"),
                context!!.getPath("/my/folder/c")
            )
    }

    @Test
    @Throws(Exception::class)
    fun testWorkspaceRoot() {
        setUpRepo("test")
        assertThat(context!!.workspaceRoot.path).isEqualTo(root.asPath())
    }

    @Test
    @Throws(Exception::class)
    fun testNoIncompatibleNoImplicitWatchLabel() {
        setUpRepo(
            "test",
            StarlarkSemantics.DEFAULT.toBuilder()
                .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, false)
                .build()
        )
        scratch.file(root.getRelative("foo").getPathString())
        val unusedPath = context!!.getPath(fakeFileLabel)
        val unusedRead = context!!.readFile(fakeFileLabel, "no", thread)
        Truth.assertThat(
            context!!.getRecordedInputs().stream()
                .filter { inputAndValue: WithValue? -> inputAndValue.input() is RepoRecordedInput.File })
            .isNotEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testIncompatibleNoImplicitWatchLabel() {
        setUpRepo(
            "test",
            StarlarkSemantics.DEFAULT.toBuilder()
                .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, true)
                .build()
        )
        scratch.file(root.getRelative("foo").getPathString())
        val unusedPath = context!!.getPath(fakeFileLabel)
        val unusedRead = context!!.readFile(fakeFileLabel, "no", thread)
        Truth.assertThat(
            context!!.getRecordedInputs().stream()
                .filter { inputAndValue: WithValue? -> inputAndValue.input() is RepoRecordedInput.File })
            .isEmpty()
    }

    companion object {
        private val thread: StarlarkThread =
            StarlarkThread.createTransient(Mutability.create("test"), StarlarkSemantics.DEFAULT)

        private const val ONE_LINE_PATCH = "@@ -1,1 +1,2 @@\n line one\n+line two\n"

        private fun exec(vararg lines: kotlin.String?): Any? {
            try {
                return Starlark.execFile(
                    ParserInput.fromLines(lines), FileOptions.DEFAULT, Module.create(), thread
                )
            } catch (ex: Exception) { // SyntaxError | EvalException | InterruptedException
                throw AssertionError("exec failed", ex)
            }
        }

        private val DUMMY_STACK: ImmutableList<CallStackEntry?> = ImmutableList.of<CallStackEntry?>(
            StarlarkThread.callStackEntry(
                StarlarkThread.TOP_LEVEL, Location.fromFileLineColumn("BUILD", 10, 1)
            ),
            StarlarkThread.callStackEntry("foo", Location.fromFileLineColumn("foo.bzl", 42, 1)),
            StarlarkThread.callStackEntry("myrule", Location.fromFileLineColumn("bar.bzl", 30, 6))
        )

        @Throws(IOException::class)
        private fun testOutputFile(path: Path, content: kotlin.String?) {
            assertThat(path.exists()).isTrue()
            InputStreamReader(path.getInputStream(), StandardCharsets.UTF_8).use { reader ->
                Truth.assertThat(
                    CharStreams.toString(reader)
                ).isEqualTo(content)
            }
        }
    }
}
