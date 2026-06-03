// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions

/** Tests for [CombinedCacheClientFactory].  */
@RunWith(JUnit4::class)
class CombinedCacheClientFactoryTest {
    private val digestUtil: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)

    private var remoteOptions: RemoteOptions? = null
    private val authAndTlsOptions: AuthAndTLSOptions? =
        com.google.devtools.common.options.Options.getDefaults<O?>(AuthAndTLSOptions::class.java)
    private var workingDirectory: Path? = null
    private var fs: InMemoryFileSystem? = null
    private val retryScheduler: com.google.common.util.concurrent.ListeningScheduledExecutorService =
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1))
    private val retrier: RemoteRetrier = RemoteRetrier(
        { RemoteRetrier.RETRIES_DISABLED },
        { e -> Result.SUCCESS },
        retryScheduler,
        Retrier.ALLOW_ALL_CALLS
    )

    @Before
    fun setUp() {
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.JavaClock(), DigestHashFunction.SHA256)
        workingDirectory = fs.getPath("/etc/something")
        remoteOptions = com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createCombinedCacheWithExistingWorkingDirectory() {
        remoteOptions.remoteCache = "http://doesnotexist.com"
        remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")
        fs.getPath("/etc/something/cache/here").createDirectoryAndParents()

        val blobStore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CombinedCacheClientFactory.create(
                remoteOptions,
                remoteOptions.getDiskCachePath(workingDirectory),  /* creds= */
                null,
                authAndTlsOptions,
                workingDirectory,
                digestUtil,
                retrier
            )

        assertThat(blobStore.remoteCacheClient()).isInstanceOf(HttpCacheClient::class.java)
        assertThat(blobStore.diskCacheClient()).isNotNull()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createCombinedCacheWithNotExistingWorkingDirectory() {
        remoteOptions.remoteCache = "http://doesnotexist.com"
        remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")
        assertThat(workingDirectory.exists()).isFalse()

        val blobStore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CombinedCacheClientFactory.create(
                remoteOptions,
                remoteOptions.getDiskCachePath(workingDirectory),  /* creds= */
                null,
                authAndTlsOptions,
                workingDirectory,
                digestUtil,
                retrier
            )

        assertThat(blobStore.remoteCacheClient()).isInstanceOf(HttpCacheClient::class.java)
        assertThat(blobStore.diskCacheClient()).isNotNull()
        assertThat(workingDirectory.exists()).isTrue()
    }

    @org.junit.Test
    fun createCombinedCacheWithMissingWorkingDirectoryShouldThrowException() {
        // interesting case: workingDirectory = null -> NPE.
        remoteOptions.remoteCache = "http://doesnotexist.com"
        remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable {
                CombinedCacheClientFactory.create(
                    remoteOptions,
                    if (remoteOptions.diskCache != null)
                        remoteOptions.getDiskCachePath( /* outputUserRoot= */null)
                    else
                        null,  /* creds= */
                    null,
                    authAndTlsOptions,  /* workingDirectory= */
                    null,
                    digestUtil,
                    retrier
                )
            })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createHttpCacheWithProxy() {
        // Unix domain sockets are not supported on Windows.
        Assume.assumeTrue(com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS)

        remoteOptions.remoteCache = "http://doesnotexist.com"
        remoteOptions.remoteProxy = "unix://some-proxy"

        val blobStore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CombinedCacheClientFactory.create(
                remoteOptions,
                remoteOptions.getDiskCachePath(workingDirectory),  /* creds= */
                null,
                authAndTlsOptions,
                workingDirectory,
                digestUtil,
                retrier
            )

        assertThat(blobStore.remoteCacheClient()).isInstanceOf(HttpCacheClient::class.java)
        assertThat(blobStore.diskCacheClient()).isNull()
    }

    @org.junit.Test
    fun createHttpCacheFailsWithUnsupportedProxyProtocol() {
        remoteOptions.remoteCache = "http://doesnotexist.com"
        remoteOptions.remoteProxy = "bad-proxy"

        Truth.assertThat(
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    CombinedCacheClientFactory.create(
                        remoteOptions,
                        remoteOptions.getDiskCachePath(workingDirectory),  /* creds= */
                        null,
                        authAndTlsOptions,
                        workingDirectory,
                        digestUtil,
                        retrier
                    )
                })
        )
            .hasMessageThat()
            .contains("Remote cache proxy unsupported: bad-proxy")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createHttpCacheWithoutProxy() {
        remoteOptions.remoteCache = "http://doesnotexist.com"

        val blobStore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CombinedCacheClientFactory.create(
                remoteOptions,
                remoteOptions.getDiskCachePath(workingDirectory),  /* creds= */
                null,
                authAndTlsOptions,
                workingDirectory,
                digestUtil,
                retrier
            )

        assertThat(blobStore.remoteCacheClient()).isInstanceOf(HttpCacheClient::class.java)
        assertThat(blobStore.diskCacheClient()).isNull()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createDiskCache() {
        remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")

        val blobStore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CombinedCacheClientFactory.create(
                remoteOptions,
                remoteOptions.getDiskCachePath(workingDirectory),  /* creds= */
                null,
                authAndTlsOptions,
                workingDirectory,
                digestUtil,
                retrier
            )

        assertThat(blobStore.remoteCacheClient()).isNull()
        assertThat(blobStore.diskCacheClient()).isNotNull()
    }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpCacheEnabled: Unit
        get() {
            remoteOptions.remoteCache = "http://doesnotexist:90"
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpCacheEnabledInUpperCase: Unit
        get() {
            remoteOptions.remoteCache = "HTTP://doesnotexist:90"
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpsCacheEnabled: Unit
        get() {
            remoteOptions.remoteCache = "https://doesnotexist:90"
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_badProtocolStartsWithHttp: Unit
        get() {
            remoteOptions.remoteCache = "httplolol://doesnotexist:90"
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_diskCacheEnabled: Unit
        get() {
            remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpAndDiskCacheEnabled: Unit
        get() {
            remoteOptions.remoteCache = "http://doesnotexist:90"
            remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")

            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpsAndDiskCacheEnabled: Unit
        get() {
            remoteOptions.remoteCache = "https://doesnotexist:90"
            remoteOptions.diskCache = PathFragment.create("/etc/something/cache/here")

            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isTrue()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpCacheDisabledWhenGrpcEnabled: Unit
        get() {
            remoteOptions.remoteCache = "grpc://doesnotexist:90"

            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_httpCacheDisabledWhenNoProtocol: Unit
        get() {
            remoteOptions.remoteCache = "doesnotexist:90"

            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_diskCacheOptionNull: Unit
        get() {
            remoteOptions.diskCache = null
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_remoteHttpCacheOptionEmpty: Unit
        get() {
            remoteOptions.remoteCache = ""
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse()
        }

    @get:org.junit.Test
    val isRemoteCacheOptions_defaultOptions: Unit
        get() {
            assertThat(CombinedCacheClientFactory.isRemoteCacheOptions(remoteOptions)).isFalse()
        }
}
