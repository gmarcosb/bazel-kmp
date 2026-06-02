// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.BlazeDirectories
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import org.apache.commons.lang3.text.WordUtils
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URI
import java.util.Base64
import java.util.List
import java.util.concurrent.Future

@RunWith(JUnit4::class)
class StarlarkBaseExternalContextTest {
    @Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    private val blazeDirectories: BlazeDirectories? = null
    private val processWrapper: ProcessWrapper? = null

    @Mock
    private val environment: Environment? = null

    @Mock
    private val downloadManager: DownloadManager? = null

    @Mock
    private val starlarkSemantics: StarlarkSemantics? = null

    @Mock
    private val remoteExecutor: RepositoryRemoteExecutor? = null
    private val mu: Mutability? = Mutability.create("test")
    var starlarkThread: StarlarkThread = StarlarkThread.create(
        mu,
        StarlarkSemantics.DEFAULT,  /* contextDescription= */
        "",
        SymbolGenerator.create<String?>("test")
    )

    @Mock
    private val extendedEventHandler: ExtendedEventHandler? = null

    /** A concrete class for testing the abstract [StarlarkBaseExternalContext].  */
    internal class TestStarlarkBaseExternalContext(
        workingDirectory: Path,
        directories: BlazeDirectories,
        env: Environment,
        repoEnv: ImmutableMap<String?, String?>,
        nonstrictRepoEnv: ImmutableMap<String?, String?>?,
        downloadManager: DownloadManager,
        timeoutScaling: Double,
        processWrapper: ProcessWrapper?,
        starlarkSemantics: StarlarkSemantics,
        identifyingStringForLogging: String?,
        remoteExecutor: RepositoryRemoteExecutor?,
        allowWatchingPathsOutsideWorkspace: Boolean
    ) : StarlarkBaseExternalContext(
        workingDirectory,
        directories,
        env,
        repoEnv,
        nonstrictRepoEnv,
        downloadManager,
        timeoutScaling,
        processWrapper,
        starlarkSemantics,
        identifyingStringForLogging,
        remoteExecutor,
        allowWatchingPathsOutsideWorkspace
    ) {
        override fun shouldDeleteWorkingDirectoryOnClose(successful: Boolean): Boolean {
            return false
        }

        val isRemotable: Boolean
            get() = true

        @get:Throws(EvalException::class)
        val remoteExecProperties: ImmutableMap<String?, String?>?
            get() = ImmutableMap.of<String?, String?>()
    }

    /** Creates a StarlarkContext with the given path.  */
    private fun setupStarlarkContext(testPath: Path): TestStarlarkBaseExternalContext {
        return TestStarlarkBaseExternalContext( /* workingDirectory= */
            testPath,  /* directories= */
            blazeDirectories,  /* env= */
            environment,  /* repoEnv= */
            null,  /* nonstrictRepoEnv= */
            null,  /* downloadManager= */
            downloadManager,  /* timeoutScaling= */
            1.0,  /* processWrapper= */
            processWrapper,  /* starlarkSemantics= */
            starlarkSemantics,  /* identifyingStringForLogging= */
            "test",  /* remoteExecutor= */
            remoteExecutor,  /* allowWatchingPathsOutsideWorkspace= */
            false
        )
    }

    /**
     * Tests the debug printing of `repository_ctx.download()` when `block` is
     * `false` and `allow_fail` is `true`.
     */
    @Test
    @Throws(Exception::class)
    fun download_asyncAllowFail_debugPrint() {
        // Setup a Starlark Context for testing.
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val testPath: Path = fs.getPath("/test")
        testPath.createDirectory()
        testPath.getRelative("output").createDirectory()
        setupStarlarkContext(testPath).use { sbec ->
            T > Mockito.`when`<T?>(environment.getListener()).thenReturn(extendedEventHandler)
            val testFuture: CompletableFuture<Path?> = CompletableFuture<Path?>()

            Mockito.`when`<Boolean?>(
                downloadManager.startDownload(
                    TODO("Cannot convert element")
                ) /* executorService= */<ExecutorService> ArgumentMatchers . any < kotlin . Any ? > (),
                TODO("Cannot convert element")
            ) /* originalUrls= */ < List < URI shr ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            String > ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            Path > ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            String > ArgumentMatchers.any<Any?>()
            TODO(
                """
            |Cannot convert element
            |With text:
            |@org.jetbrains.annotations.NotNull Phaser>any(),
            |              /* mayHardlink= */ anyBoolean()
            """.trimMargin()
            )
            thenReturn(testFuture)

            val download: Any? =
                sbec.download( /* url= */
                    "http://example.com/file.txt",  /* output= */
                    "/test/output",  /* sha256= */
                    SHA256_EMPTY_FILE,  /* executable= */
                    false,  /* allowFail= */
                    true,  /* canonicalId= */
                    "id",  /* authUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* headersUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* integrity= */
                    "",  /* block= */
                    false,  /* thread= */
                    starlarkThread
                )
            Truth.assertThat(download).isInstanceOf(StarlarkValue::class.java)
            val starlarkPendingDownload: StarlarkValue? = download as StarlarkValue?

            // Check that debugPrint shows the RUNNING state.
            val runningPrinter = Printer()
            starlarkPendingDownload.debugPrint(runningPrinter, starlarkThread)
            Truth.assertThat(runningPrinter.toString()).contains("(state: RUNNING)")

            // Complete the future and check debugPrint shows the SUCCESS state.
            testFuture.complete(fs.getPath("/test/output"))
            val successPrinter = Printer()
            starlarkPendingDownload.debugPrint(successPrinter, starlarkThread)
            Truth.assertThat(successPrinter.toString()).contains("(state: SUCCESS)")

            // Create a download that will fail.
            val failingFuture: CompletableFuture<Path?> = CompletableFuture<Path?>()
            Mockito.`when`<Boolean?>(
                downloadManager.startDownload(
                    TODO("Cannot convert element")
                ) /* executorService= */<ExecutorService> ArgumentMatchers . any < kotlin . Any ? > (),
                TODO("Cannot convert element")
            ) /* originalUrls= */ < List < URI shr ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            String > ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            Path > ArgumentMatchers.any<Any?>()
            ArgumentMatchers.any<Any?>()
            String > ArgumentMatchers.any<Any?>()
            TODO(
                """
            |Cannot convert element
            |With text:
            |@org.jetbrains.annotations.NotNull Phaser>any(),
            |              /* mayHardlink= */ anyBoolean()
            """.trimMargin()
            )
            thenReturn(failingFuture)
            val failingDownload: Any? =
                sbec.download( /* url= */
                    "http://example.com/file.txt",  /* output= */
                    "/test/output",  /* sha256= */
                    SHA256_EMPTY_FILE,  /* executable= */
                    false,  /* allowFail= */
                    true,  /* canonicalId= */
                    "id",  /* authUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* headersUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* integrity= */
                    "",  /* block= */
                    false,  /* thread= */
                    starlarkThread
                )
            val starlarkFailingDownload: StarlarkValue? = failingDownload as StarlarkValue?
            failingFuture.completeExceptionally(Throwable())

            // Check debugPrint shows FAILED.
            val failingPrinter = Printer()
            starlarkFailingDownload.debugPrint(failingPrinter, starlarkThread)
            Truth.assertThat(failingPrinter.toString()).contains("(state: FAILED)")
        }
    }

    /**
     * Tests calling the async `repository_ctx.download()`, then calling `wait`
     * on the pending download and checking the return values.
     */
    @Test
    @Throws(Exception::class)
    fun download_asyncWait_returnValue() {
        // Setup a Starlark Context for testing.
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val testPath: Path = fs.getPath("/test")
        testPath.createDirectory()
        testPath.getRelative("output").createDirectory()

        // Simulate the downloaded file.
        val testFile: Path = fs.getPath("/test/output/file.gz")
        val o: OutputStream = testFile.getOutputStream()
        o.write(getEmptyTarGzBytes())
        o.close()

        Object > Mockito.`when`<Boolean?>(downloadManager.finalizeDownload(TODO("Cannot convert element")) < Future < Path shr ArgumentMatchers.any<Any?>())
        thenReturn(testFile)
        Mockito.`when`<Boolean?>(
            downloadManager.startDownload(
                TODO("Cannot convert element")
            ) /* executorService= */<ExecutorService> ArgumentMatchers . any < kotlin . Any ? > (),
            TODO("Cannot convert element")
        ) /* originalUrls= */ < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        TODO(
            """
            |Cannot convert element
            |With text:
            |@org.jetbrains.annotations.NotNull Phaser>any(),
            |            /* mayHardlink= */ anyBoolean()
            """.trimMargin()
        )
        thenReturn(CompletableFuture<T?>())

        setupStarlarkContext(testPath).use { sbec ->
            T > Mockito.`when`<T?>(environment.getListener()).thenReturn(extendedEventHandler)
            val download: Any? =
                sbec.download( /* url= */
                    "http://example.com/file",  /* output= */
                    "/test/output",  /* sha256= */
                    SHA256_EMPTY_GZ_FILE,  /* executable= */
                    false,  /* allowFail= */
                    true,  /* canonicalId= */
                    "id",  /* authUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* headersUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* integrity= */
                    "",  /* block= */
                    false,  /* thread= */
                    starlarkThread
                )
            val returnValue: Any? = Companion.callStarlarkMethod(download!!, "wait")
            Truth.assertThat(returnValue).isInstanceOf(StructImpl::class.java)
            val struct: StructImpl = returnValue as StructImpl
            val p = Printer()
            struct.repr(p, StarlarkSemantics.DEFAULT)
            assertThat(struct.getValue("success", Boolean::class.java)).isEqualTo(true)
            assertThat(struct.getValue("error", String::class.java)).isNull()
            assertThat(struct.getValue("sha256", String::class.java)).isEqualTo(SHA256_EMPTY_GZ_FILE)
            assertThat(struct.getValue("integrity", String::class.java))
                .isEqualTo(
                    "sha256-"
                            + Base64.getEncoder()
                        .encodeToString(HexFormat.of().parseHex(SHA256_EMPTY_GZ_FILE))
                )
            assertThat(struct.getValue("size_bytes", StarlarkInt::class.java))
                .isEqualTo(StarlarkInt.of(emptyTarGzBytes.size))
        }
    }

    /**
     * Tests the return value of `repository_ctx.download_and_extract()` when successful.
     */
    @Test
    @Throws(Exception::class)
    fun downloadAndExtract_successReturnValue() {
        T > Mockito.`when`<T?>(environment.getListener()).thenReturn(extendedEventHandler)
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val testPath: Path = fs.getPath("/test")
        testPath.createDirectory()
        fs.getPath("/test/output").createDirectory()
        val testFile: Path = fs.getPath("/test/output/path.gz")

        val o: OutputStream = testFile.getOutputStream()
        o.write(emptyTarGzBytes)
        o.close()
        Object > Mockito.`when`<Boolean?>(downloadManager.finalizeDownload(TODO("Cannot convert element")) < Future < Path shr ArgumentMatchers.any<Any?>())
        thenReturn(testFile)

        Mockito.`when`<Boolean?>(
            downloadManager.startDownload(
                TODO("Cannot convert element")
            ) /* executorService= */<ExecutorService> ArgumentMatchers . any < kotlin . Any ? > (),
            TODO("Cannot convert element")
        ) /* originalUrls= */ < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        TODO(
            """
            |Cannot convert element
            |With text:
            |@org.jetbrains.annotations.NotNull Phaser>any(),
            |            /* mayHardlink= */ anyBoolean()
            """.trimMargin()
        )
        thenReturn(CompletableFuture<T?>())
        setupStarlarkContext(testPath).use { sbec ->
            val struct: StructImpl =
                sbec.downloadAndExtract( /* url= */
                    "http://example.com/file.txt.gz",  /* output= */
                    "/test/output",  /* sha256= */
                    SHA256_EMPTY_GZ_FILE,  /* type= */
                    "gz",  /* stripPrefix= */
                    "",  /* allowFail= */
                    false,  /* canonicalId= */
                    "id",  /* authUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* headersUnchecked= */
                    Dict.builder<String?, String?>().buildImmutable(),  /* integrity= */
                    "",  /* renameFiles= */
                    Dict.builder<String?, String?>().buildImmutable(),  /* oldStripPrefix= */
                    "",  /* stripComponentsI= */
                    StarlarkInt.of(0),  /* thread= */
                    starlarkThread
                )
            val p = Printer()
            struct.repr(p, StarlarkSemantics.DEFAULT)
            assertThat(struct.getValue("success", Boolean::class.java)).isEqualTo(true)
            assertThat(struct.getValue("error", String::class.java)).isNull()
            assertThat(struct.getValue("sha256", String::class.java)).isEqualTo(SHA256_EMPTY_GZ_FILE)
            assertThat(struct.getValue("integrity", String::class.java))
                .isEqualTo(
                    "sha256-"
                            + Base64.getEncoder()
                        .encodeToString(HexFormat.of().parseHex(SHA256_EMPTY_GZ_FILE))
                )
            assertThat(struct.getValue("size_bytes", StarlarkInt::class.java))
                .isEqualTo(StarlarkInt.of(emptyTarGzBytes.size))
        }
    }

    /**
     * Tests the return value of `download_and_extract` when `allow_fail` is
     * `true` and a failure occurs.
     */
    @Test
    @Throws(Exception::class)
    fun downloadAndExtract_allowFail_unsuccessfulReturnValue() {
        T > Mockito.`when`<T?>(environment.getListener()).thenReturn(extendedEventHandler)
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val testPath: Path = fs.getPath("/test")
        testPath.createDirectory()
        fs.getPath("/test/output").createDirectory()
        Object > Mockito.`when`<Boolean?>(downloadManager.finalizeDownload(TODO("Cannot convert element")) < Future < Path shr ArgumentMatchers.any<Any?>())
        thenThrow(IOException("test exception"))
        Mockito.`when`<Boolean?>(
            downloadManager.startDownload(
                TODO("Cannot convert element")
            ) /* executorService= */<ExecutorService> ArgumentMatchers . any < kotlin . Any ? > (),
            TODO("Cannot convert element")
        ) /* originalUrls= */ < List < URI shr ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        Path > ArgumentMatchers.any<Any?>()
        ArgumentMatchers.any<Any?>()
        String > ArgumentMatchers.any<Any?>()
        TODO(
            """
            |Cannot convert element
            |With text:
            |@org.jetbrains.annotations.NotNull Phaser>any(),
            |            /* mayHardlink= */ anyBoolean()
            """.trimMargin()
        )
        thenReturn(CompletableFuture<T?>())
        setupStarlarkContext(testPath).use { sbec ->
            val struct: StructImpl =
                sbec.downloadAndExtract( /* url= */
                    "http://example.com/file.txt.gz",  /* output= */
                    "/test/output",  /* sha256= */
                    SHA256_EMPTY_FILE,  /* type= */
                    "gz",  /* stripPrefix= */
                    "",  /* allowFail= */
                    true,  /* canonicalId= */
                    "id",  /* authUnchecked= */
                    Dict.builder<String?, Dict<String?, Any?>?>().buildImmutable(),  /* headersUnchecked= */
                    Dict.builder<String?, String?>().buildImmutable(),  /* integrity= */
                    "",  /* renameFiles= */
                    Dict.builder<String?, String?>().buildImmutable(),  /* oldStripPrefix= */
                    "",  /* stripComponentsI= */
                    StarlarkInt.of(0),  /* thread= */
                    starlarkThread
                )
            val p = Printer()
            struct.repr(p, StarlarkSemantics.DEFAULT)
            assertThat(struct.getValue("success", Boolean::class.java)).isEqualTo(false)
            assertThat(struct.getValue("error", String::class.java))
                .isEqualTo("java.io.IOException: test exception")
        }
    }

    @Test
    fun docSupportedFormats() {
        val expected: String? = DecompressorValue.readableSupportedFormats("\"", "\"", "or")
        val observed = StarlarkBaseExternalContext.SUPPORTED_DECOMPRESSION_FORMATS
        val copyPasteCode =
            ("  static final String SUPPORTED_DECOMPRESSION_FORMATS =\n"
                    + "\"\"\"\n"
                    + WordUtils.wrap(
                expected,  /* wrapLength= */
                80,  /* newLineStr= */
                " \\\n",  /* wrapLongWords= */
                false
            )
                    + "\\\n\"\"\";")

        if (observed != expected) {
            Assert.fail(
                String.format(
                    """


              Expected:
              ${'\t'}%1${'$'}s
              Got:
              ${'\t'}%2${'$'}s

              Copy-paste string to replace in StarlarkBaseExternalContext.java:  

              %3${'$'}s
              
              """.trimIndent(),
                    expected, observed, copyPasteCode
                )
            )
        }
    }

    companion object {
        /** The sha256 of an empty file (`sha256sum /dev/null`).  */
        const val SHA256_EMPTY_FILE: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /**
         * Byte array of an empty gzipped file. You can generate the same sequence of bytes with (sed part
         * is linux-specific):
         * 
         * <pre>`$ gzip -n < /dev/null > empty.gz $ od -v -t d1 empty.gz | cut -c9- | sed 's/\([0-9]\+\)/\1,/g' `</pre>
         */
        private val emptyTarGzBytes = byteArrayOf(31, -117, 8, 0, 0, 0, 0, 0, 0, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        /** Returns a copy of the empty tar.gz bytes.  */
        fun getEmptyTarGzBytes(): ByteArray? {
            return emptyTarGzBytes.clone()
        }

        /** The sha256 of a gzipped, empty file (`gzip -n < /dev/null | sha256sum`).  */
        const val SHA256_EMPTY_GZ_FILE: String = "59869db34853933b239f1e2219cf7d431da006aa919635478511fabbfc8849d2"

        /** Calls the given starlark method name on the given object via Java reflection.  */
        @Throws(InvocationTargetException::class, IllegalAccessException::class)
        private fun callStarlarkMethod(
            instance: Any, starlarkMethodName: String, vararg args: Any?
        ): Any? {
            val methodAnnotations: ImmutableMap<Method?, StarlarkMethod?> =
                Starlark.getMethodAnnotations(instance.javaClass)
            for (entry in methodAnnotations.entries) {
                val javaMethod: Method = entry.key!!
                val starlarkMethod: StarlarkMethod = entry.value
                if (starlarkMethodName == starlarkMethod.name) {
                    return javaMethod.invoke(instance, *args)
                }
            }
            throw IllegalArgumentException(
                String.format("Couldn't find the starlark method %s on %s", starlarkMethodName, instance)
            )
        }
    }
}
