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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.authandtls.BasicHttpAuthenticationEncoder

/** Tests for [IndexRegistry].  */
@RunWith(JUnit4::class)
class IndexRegistryTest : FoundationTestCase() {
    private class EventRecorder {
        private val downloadEvents: MutableList<RegistryFileDownloadEvent?> =
            java.util.ArrayList<RegistryFileDownloadEvent?>()

        @com.google.common.eventbus.Subscribe
        fun onRegistryFileDownloadEvent(downloadEvent: RegistryFileDownloadEvent?) {
            downloadEvents.add(downloadEvent)
        }

        val recordedHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>
            get() = downloadEvents.stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        RegistryFileDownloadEvent::uri,
                        RegistryFileDownloadEvent::checksum
                    )
                )
    }

    private val authToken: String? = BasicHttpAuthenticationEncoder.encode("rinne", "rinnepass")
    private var downloadManager: DownloadManager? = null
    private var eventRecorder: EventRecorder? = null

    @org.junit.Rule
    val server: TestHttpServer = TestHttpServer(authToken)

    @org.junit.Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private var registryFactory: RegistryFactoryImpl? = null
    private var downloadCache: DownloadCache? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        eventRecorder = EventRecorder()
        eventBus.register(eventRecorder)
        downloadCache = DownloadCache()
        val httpDownloader: HttpDownloader = HttpDownloader()
        downloadManager = DownloadManager(downloadCache, httpDownloader, httpDownloader, reporter)
        registryFactory =
            RegistryFactoryImpl(com.google.common.base.Suppliers.ofInstance<T?>(com.google.common.collect.ImmutableMap.of<Any?, Any?>()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHttpUrl() {
        server.serve("/myreg/modules/foo/1.0/MODULE.bazel", "lol")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl() + "/myreg",
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(registry.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "1.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "lol".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel"
                )
            )
        org.junit.Assert.assertThrows<T?>(
            Registry.NotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                registry.getModuleFile(
                    BzlmodTestUtil.createModuleKey("bar", "1.0"),
                    reporter,
                    downloadManager
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHttpUrlWithNetrcCreds() {
        server.serve(
            "/myreg/modules/foo/1.0/MODULE.bazel",
            "lol".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
            true
        )
        server.start()
        val netrc: Netrc? =
            NetrcParser.parseAndClose(
                ByteArrayInputStream(
                    "machine [::1] login rinne password rinnepass\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl() + "/myreg",
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )

        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getModuleFile(
                        BzlmodTestUtil.createModuleKey(
                            "foo",
                            "1.0"
                        ), reporter, downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Failed to fetch registry file %s: GET returned 401 Unauthorized"
                    .formatted(server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel")
            )

        downloadManager.setNetrcCreds(NetrcCredentials(netrc))
        assertThat(registry.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "1.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "lol".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel"
                )
            )
        org.junit.Assert.assertThrows<T?>(
            Registry.NotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                registry.getModuleFile(
                    BzlmodTestUtil.createModuleKey("bar", "1.0"),
                    reporter,
                    downloadManager
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileUrl() {
        tempFolder.newFolder("fakereg", "modules", "foo", "1.0")
        val file: java.io.File = tempFolder.newFile("fakereg/modules/foo/1.0/MODULE.bazel")
        java.nio.file.Files.newBufferedWriter(file.toPath(), java.nio.charset.StandardCharsets.UTF_8).use { writer ->
            writer.write("lol")
        }
        val registry: Registry =
            registryFactory.createRegistry(
                java.io.File(tempFolder.getRoot(), "fakereg").toURI().toString(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(registry.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "1.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "lol".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    file.toURI().toString()
                )
            )
        org.junit.Assert.assertThrows<T?>(
            Registry.NotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                registry.getModuleFile(
                    BzlmodTestUtil.createModuleKey("bar", "1.0"),
                    reporter,
                    downloadManager
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec() {
        server.serve(
            "/bazel_registry.json",
            "{",
            "  \"mirrors\": [",
            "    \"https://mirror.bazel.build/\",",
            "    \"file:///home/bazel/mymirror/\"",
            "  ]",
            "}"
        )
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"mirror_urls\":"
                    + " [\"http://my.mirror/mysite.com/thing.zip\",\"http://another.mirror/mysite.com/thing.zip\"],",
            "  \"integrity\": \"sha256-blah\",",
            "  \"strip_prefix\": \"pref\"",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.serve(
            "/modules/bar/2.0/source.json",
            "{",
            "  \"url\": \"https://example.com/archive.jar?with=query\",",
            "  \"integrity\": \"sha256-bleh\",",
            "  \"patches\": {",
            "    \"1.fix-this.patch\": \"sha256-lol\",",
            "    \"2.fix-that.patch\": \"sha256-kek\"",
            "  },",
            "  \"patch_strip\": 3",
            "}"
        )
        server.serve("/modules/bar/2.0/MODULE.bazel", "module(name = \"bar\", version = \"2.0\")")
        server.serve(
            "/modules/baz/3.0/source.json",
            """
        {
            "url": "https://example.com/archive.jar?with=query",
            "integrity": "sha256-bleh",
            "overlay": {
                "BUILD.bazel": "sha256-bleh-overlay"
            }
        }
        
        """.trimIndent()
        )
        server.serve("/modules/baz/3.0/MODULE.bazel", "module(name = \"baz\", version = \"3.0\")")
        server.start()
        val moduleFileRegistryHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("module(name = \"foo\", version = \"1.0\")")),
                server.getUrl() + "/modules/baz/3.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("module(name = \"baz\", version = \"3.0\")")),
                server.getUrl() + "/modules/bar/2.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("module(name = \"bar\", version = \"2.0\")"))
            )

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>("https://my.mirror")
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("foo", "1.0"), moduleFileRegistryHashes, reporter, downloadManager
            )
        )
            .isEqualTo(
                ArchiveRepoSpecBuilder()
                    .setUrls(
                        com.google.common.collect.ImmutableList.of<E?>(
                            "https://my.mirror/mysite.com/thing.zip",
                            "https://mirror.bazel.build/mysite.com/thing.zip",
                            "file:///home/bazel/mymirror/mysite.com/thing.zip",
                            "http://mysite.com/thing.zip",
                            "http://my.mirror/mysite.com/thing.zip",
                            "http://another.mirror/mysite.com/thing.zip"
                        )
                    )
                    .setIntegrity("sha256-blah")
                    .setStripPrefix("pref")
                    .setRemotePatches(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setOverlay(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setRemoteModuleFile(
                        RemoteFile(
                            sha256("module(name = \"foo\", version = \"1.0\")")
                                .toSubresourceIntegrity(),
                            com.google.common.collect.ImmutableList.of<E?>(server.getUrl() + "/modules/foo/1.0/MODULE.bazel")
                        )
                    )
                    .setRemotePatchStrip(0)
                    .build()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("bar", "2.0"), moduleFileRegistryHashes, reporter, downloadManager
            )
        )
            .isEqualTo(
                ArchiveRepoSpecBuilder()
                    .setUrls(
                        com.google.common.collect.ImmutableList.of<E?>(
                            "https://my.mirror/example.com/archive.jar?with=query",
                            "https://mirror.bazel.build/example.com/archive.jar?with=query",
                            "file:///home/bazel/mymirror/example.com/archive.jar?with=query",
                            "https://example.com/archive.jar?with=query"
                        )
                    )
                    .setIntegrity("sha256-bleh")
                    .setStripPrefix("")
                    .setRemotePatches(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/bar/2.0/patches/1.fix-this.patch", "sha256-lol",
                            server.getUrl() + "/modules/bar/2.0/patches/2.fix-that.patch",
                            "sha256-kek"
                        )
                    )
                    .setRemotePatchStrip(3)
                    .setOverlay(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setRemoteModuleFile(
                        RemoteFile(
                            sha256("module(name = \"bar\", version = \"2.0\")")
                                .toSubresourceIntegrity(),
                            com.google.common.collect.ImmutableList.of<E?>(server.getUrl() + "/modules/bar/2.0/MODULE.bazel")
                        )
                    )
                    .build()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("baz", "3.0"), moduleFileRegistryHashes, reporter, downloadManager
            )
        )
            .isEqualTo(
                ArchiveRepoSpecBuilder()
                    .setUrls(
                        com.google.common.collect.ImmutableList.of<E?>(
                            "https://my.mirror/example.com/archive.jar?with=query",
                            "https://mirror.bazel.build/example.com/archive.jar?with=query",
                            "file:///home/bazel/mymirror/example.com/archive.jar?with=query",
                            "https://example.com/archive.jar?with=query"
                        )
                    )
                    .setIntegrity("sha256-bleh")
                    .setStripPrefix("")
                    .setOverlay(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "BUILD.bazel",
                            RemoteFile(
                                "sha256-bleh-overlay",  // URLs in the registry itself are not mirrored.
                                com.google.common.collect.ImmutableList.of<E?>(
                                    server.getUrl() + "/modules/baz/3.0/overlay/BUILD.bazel"
                                )
                            )
                        )
                    )
                    .setRemoteModuleFile(
                        RemoteFile(
                            sha256("module(name = \"baz\", version = \"3.0\")")
                                .toSubresourceIntegrity(),
                            com.google.common.collect.ImmutableList.of<E?>(server.getUrl() + "/modules/baz/3.0/MODULE.bazel")
                        )
                    )
                    .setRemotePatches(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setRemotePatchStrip(0)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetGitRepoSpec() {
        server.serve(
            "/bazel_registry.json",
            """
        {
          "mirrors": [
            "https://mirror.bazel.build/",
            "file:///home/bazel/mymirror/"
          ]
        }
        
        """.trimIndent()
        )
        server.serve(
            "/modules/foo/1.0/source.json",
            """
        {
            "type": "git_repository",
            "remote": "https://github.com/raspberrypi/pico-sdk.git",
            "commit": "4b6e647590213f253f2789ad9026df1d00f38c5d",
            "patches": {
                "foo.patch": "sha256-totallyarealhash"
            },
            "patch_strip": 1,
            "add_prefix": "addedSubfolder"
        }
        
        """.trimIndent()
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()
        val moduleFileRegistryHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("module(name = \"foo\", version = \"1.0\")"))
            )

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("foo", "1.0"), moduleFileRegistryHashes, reporter, downloadManager
            )
        )
            .isEqualTo(
                GitRepoSpecBuilder()
                    .setRemote("https://github.com/raspberrypi/pico-sdk.git")
                    .setCommit("4b6e647590213f253f2789ad9026df1d00f38c5d")
                    .setInitSubmodules(false)
                    .setVerbose(false)
                    .setRemoteModuleFile(
                        RemoteFile(
                            sha256("module(name = \"foo\", version = \"1.0\")")
                                .toSubresourceIntegrity(),
                            com.google.common.collect.ImmutableList.of<E?>(server.getUrl() + "/modules/foo/1.0/MODULE.bazel")
                        )
                    )
                    .setRemotePatches(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/patches/foo.patch",
                            "sha256-totallyarealhash"
                        )
                    )
                    .setRemotePatchStrip(1)
                    .setAddPrefix("addedSubfolder")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLocalPathRepoSpec() {
        server.serve("/bazel_registry.json", "{", "  \"module_base_path\": \"/hello/foo\"", "}")
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"type\": \"local_path\",",
            "  \"path\": \"../bar/project_x\"",
            "}"
        )
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("foo", "1.0"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                reporter,
                downloadManager
            )
        )
            .isEqualTo(LocalPathRepoSpecs.create("/hello/bar/project_x"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRepoInvalidRegistryJsonSpec() {
        server.serve("/bazel_registry.json", "", "", "", "")
        server.start()
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"strip_prefix\": \"pref\"",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("foo", "1.0"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                    java.util.Optional.of<T?>(sha256("module(name = \"foo\", version = \"1.0\")"))
                ),
                reporter,
                downloadManager
            )
        )
            .isEqualTo(
                ArchiveRepoSpecBuilder()
                    .setUrls(com.google.common.collect.ImmutableList.of<E?>("http://mysite.com/thing.zip"))
                    .setIntegrity("sha256-blah")
                    .setStripPrefix("pref")
                    .setRemotePatches(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setOverlay(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setRemoteModuleFile(
                        RemoteFile(
                            sha256("module(name = \"foo\", version = \"1.0\")")
                                .toSubresourceIntegrity(),
                            com.google.common.collect.ImmutableList.of<E?>(server.getUrl() + "/modules/foo/1.0/MODULE.bazel")
                        )
                    )
                    .setRemotePatchStrip(0)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRepoInvalidModuleJsonSpec() {
        server.serve(
            "/bazel_registry.json",
            "{",
            "  \"mirrors\": [",
            "    \"https://mirror.bazel.build/\",",
            "    \"file:///home/bazel/mymirror/\"",
            "  ]",
            "}"
        )
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"strip_prefix\": \"pref\",",
            "}"
        )
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                registry.getRepoSpec(
                    BzlmodTestUtil.createModuleKey("foo", "1.0"),
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    reporter,
                    downloadManager
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetYankedVersion() {
        server.serve(
            "/modules/red-pill/metadata.json",
            ("{\n"
                    + "    'homepage': 'https://docs.matrix.org/red-pill',\n"
                    + "    'maintainers': [\n"
                    + "        {\n"
                    + "            'email': 'neo@matrix.org',\n"
                    + "            'github': 'neo',\n"
                    + "            'name': 'Neo'\n"
                    + "        }\n"
                    + "    ],\n"
                    + "    'versions': [\n"
                    + "        '1.0',\n"
                    + "        '2.0'\n"
                    + "    ],\n"
                    + "    'yanked_versions': {"
                    + "        '1.0': 'red-pill 1.0 is yanked due to CVE-2000-101, please upgrade to 2.0'\n"
                    + "    }\n"
                    + "}")
        )
        server.start()
        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val yankedVersion: java.util.Optional<com.google.common.collect.ImmutableMap<Version?, String?>?>? =
            registry.getYankedVersions("red-pill", reporter, downloadManager)
        Truth.assertThat(yankedVersion)
            .hasValue(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    Version.parse("1.0"),
                    "red-pill 1.0 is yanked due to CVE-2000-101, please upgrade to 2.0"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArchiveWithExplicitType() {
        server.serve(
            "/modules/archive_type/1.0/source.json",
            "{",
            "  \"url\": \"https://mysite.com/thing?format=zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"archive_type\": \"zip\"",
            "}"
        )
        server.serve(
            "/modules/archive_type/1.0/MODULE.bazel",
            "module(name = \"archive_type\", version = \"1.0\")"
        )
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("archive_type", "1.0"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    server.getUrl() + "/modules/archive_type/1.0/MODULE.bazel",
                    java.util.Optional.of<T?>(sha256("module(name = \"archive_type\", version = \"1.0\")"))
                ),
                reporter,
                downloadManager
            )
        )
            .isEqualTo(
                ArchiveRepoSpecBuilder()
                    .setUrls(com.google.common.collect.ImmutableList.of<E?>("https://mysite.com/thing?format=zip"))
                    .setIntegrity("sha256-blah")
                    .setStripPrefix("")
                    .setArchiveType("zip")
                    .setRemotePatches(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setRemotePatchStrip(0)
                    .setOverlay(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .setRemoteModuleFile(
                        RemoteFile(
                            sha256("module(name = \"archive_type\", version = \"1.0\")")
                                .toSubresourceIntegrity(),
                            com.google.common.collect.ImmutableList.of<E?>(
                                server.getUrl() + "/modules/archive_type/1.0/MODULE.bazel"
                            )
                        )
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetModuleFileChecksums() {
        downloadCache.setPath(scratch.dir("cache"))

        server.serve("/myreg/modules/foo/1.0/MODULE.bazel", "old")
        server.serve("/myreg/modules/foo/2.0/MODULE.bazel", "new")
        server.start()

        val knownFiles: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("old")),
                server.getUrl() + "/myreg/modules/unused/1.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("unused"))
            )
        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl() + "/myreg",
                LockfileMode.UPDATE,
                knownFiles,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(registry.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "1.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "old".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel"
                )
            )
        assertThat(registry.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "2.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "new".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    server.getUrl() + "/myreg/modules/foo/2.0/MODULE.bazel"
                )
            )
        val e: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */?
        T > org.junit.Assert.assertThrows<T?>(
            Registry.NotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                registry.getModuleFile(
                    BzlmodTestUtil.createModuleKey("bar", "1.0"),
                    reporter,
                    downloadManager
                )
            })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(server.getUrl() + "/myreg/modules/bar/1.0/MODULE.bazel: not found")

        val recordedChecksums: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            eventRecorder!!.recordedHashes
        Truth.assertThat(
            com.google.common.collect.Maps.transformValues<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?, java.util.Optional<String>?>(
                recordedChecksums,
                com.google.common.base.Function { maybeChecksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>? ->
                    maybeChecksum.map<String>(java.util.function.Function { obj: com.google.devtools.build.lib.bazel.repository.downloader.Checksum? -> obj.toString() })
                })
        )
            .containsExactly(
                server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel",
                java.util.Optional.of<String>(sha256("old").toString()),
                server.getUrl() + "/myreg/modules/foo/2.0/MODULE.bazel",
                java.util.Optional.of<String>(sha256("new").toString()),
                server.getUrl() + "/myreg/modules/bar/1.0/MODULE.bazel",
                java.util.Optional.empty<Any?>()
            )
            .inOrder()

        val registry2: Registry =
            registryFactory.createRegistry(
                server.getUrl() + "/myreg",
                LockfileMode.UPDATE,
                recordedChecksums,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        // Test that the recorded hashes are used for repo cache hits even when the server content
        // changes.
        server.unserve("/myreg/modules/foo/1.0/MODULE.bazel")
        server.unserve("/myreg/modules/foo/2.0/MODULE.bazel")
        server.serve("/myreg/modules/bar/1.0/MODULE.bazel", "no longer 404")
        assertThat(registry2.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "1.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "old".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel"
                )
            )
        assertThat(registry2.getModuleFile(BzlmodTestUtil.createModuleKey("foo", "2.0"), reporter, downloadManager))
            .isEqualTo(
                ModuleFile.create(
                    "new".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    server.getUrl() + "/myreg/modules/foo/2.0/MODULE.bazel"
                )
            )
        (.also {
            e = it
        }
        < T > org.junit.Assert.assertThrows<T?>(
            Registry.NotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                registry2.getModuleFile(
                    BzlmodTestUtil.createModuleKey("bar", "1.0"),
                    reporter,
                    downloadManager
                )
            }))
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                (server.getUrl()
                        + "/myreg/modules/bar/1.0/MODULE.bazel: previously not found (as recorded in"
                        + " MODULE.bazel.lock, refresh with --lockfile_mode=refresh)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetModuleFileChecksumMismatch() {
        downloadCache.setPath(scratch.dir("cache"))

        server.serve("/myreg/modules/foo/1.0/MODULE.bazel", "fake")
        server.start()

        val knownFiles: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("original"))
            )
        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl() + "/myreg",
                LockfileMode.UPDATE,
                knownFiles,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getModuleFile(
                        BzlmodTestUtil.createModuleKey(
                            "foo",
                            "1.0"
                        ), reporter, downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Failed to fetch registry file %s: Checksum was %s but wanted %s"
                    .formatted(
                        server.getUrl() + "/myreg/modules/foo/1.0/MODULE.bazel",
                        sha256("fake"),
                        sha256("original")
                    )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRepoSpecChecksum() {
        downloadCache.setPath(scratch.dir("cache"))

        val registryJson: String =
            """
        {
          "module_base_path": "/hello/foo"
        }
        
        """.trimIndent()
        server.serve("/bazel_registry.json", registryJson)
        val sourceJson: String =
            """
        {
          "type": "local_path",
          "path": "../bar/project_x"
        }
        
        """.trimIndent()
        server.serve("/modules/foo/1.0/source.json", sourceJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        server.start()

        val knownFiles: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/modules/foo/2.0/source.json",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(sha256("unused"))
            )
        var registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                knownFiles,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("foo", "1.0"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                reporter,
                downloadManager
            )
        )
            .isEqualTo(LocalPathRepoSpecs.create("/hello/bar/project_x"))

        val recordedChecksums: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            eventRecorder!!.recordedHashes
        Truth.assertThat(
            com.google.common.collect.Maps.transformValues<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?, java.util.Optional<String>?>(
                recordedChecksums,
                com.google.common.base.Function { checksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>? ->
                    checksum.map<String>(java.util.function.Function { obj: com.google.devtools.build.lib.bazel.repository.downloader.Checksum? -> obj.toString() })
                })
        )
            .containsExactly(
                server.getUrl() + "/bazel_registry.json",
                java.util.Optional.of<String>(sha256(registryJson).toString()),
                server.getUrl() + "/modules/foo/1.0/source.json",
                java.util.Optional.of<String>(sha256(sourceJson).toString())
            )

        registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                recordedChecksums,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        // Test that the recorded hashes are used for repo cache hits even when the server content
        // changes.
        server.unserve("/bazel_registry.json")
        server.unserve("/modules/foo/1.0/source.json")
        assertThat(
            registry.getRepoSpec(
                BzlmodTestUtil.createModuleKey("foo", "1.0"),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                reporter,
                downloadManager
            )
        )
            .isEqualTo(LocalPathRepoSpecs.create("/hello/bar/project_x"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRepoSpecChecksumMismatch() {
        downloadCache.setPath(scratch.dir("cache"))

        val registryJson: String =
            """
        {
          "module_base_path": "/hello/foo"
        }
        
        """.trimIndent()
        server.serve("/bazel_registry.json", registryJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val sourceJson: String =
            """
        {
          "type": "local_path",
          "path": "../bar/project_x"
        }
        
        """.trimIndent()
        val maliciousSourceJson: String = sourceJson.replace("project_x", "malicious")
        server.serve(
            "/modules/foo/1.0/source.json",
            maliciousSourceJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        server.start()

        val knownFiles: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/bazel_registry.json",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                    sha256(
                        registryJson
                    )
                ),
                server.getUrl() + "/modules/foo/1.0/source.json",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                    sha256(
                        sourceJson
                    )
                )
            )
        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                knownFiles,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Failed to fetch registry file %s: Checksum was %s but wanted %s"
                    .formatted(
                        server.getUrl() + "/modules/foo/1.0/source.json",
                        sha256(maliciousSourceJson),
                        sha256(sourceJson)
                    )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBazelRegistryChecksumMismatch() {
        downloadCache.setPath(scratch.dir("cache"))

        val registryJson: String =
            """
        {
          "module_base_path": "/hello/foo"
        }
        
        """.trimIndent()
        val maliciousRegistryJson: String = registryJson.replace("foo", "malicious")
        server.serve("/bazel_registry.json", maliciousRegistryJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val sourceJson: String =
            """
        {
          "type": "local_path",
          "path": "../bar/project_x"
        }
        
        """.trimIndent()
        server.serve("/modules/foo/1.0/source.json", sourceJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        server.start()

        val knownFiles: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
            com.google.common.collect.ImmutableMap.of<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>(
                server.getUrl() + "/bazel_registry.json",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                    sha256(
                        registryJson
                    )
                ),
                server.getUrl() + "/modules/foo/1.0/source.json",
                java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                    sha256(
                        sourceJson
                    )
                )
            )
        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                knownFiles,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Failed to fetch registry file %s: Checksum was %s but wanted %s"
                    .formatted(
                        server.getUrl() + "/bazel_registry.json",
                        sha256(maliciousRegistryJson),
                        sha256(registryJson)
                    )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec_emptyMainIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"\"",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("Missing integrity for module foo@1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec_emptyPatchIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"patches\": {",
            "    \"fix.patch\": \"\"",
            "  }",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Missing integrity for patch fix.patch of module foo@1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec_emptyOverlayIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"overlay\": {",
            "    \"BUILD.bazel\": \"\"",
            "  }",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Missing integrity for overlay file BUILD.bazel of module foo@1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetGitRepoSpec_emptyPatchIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            """
        {
            "type": "git_repository",
            "remote": "https://github.com/raspberrypi/pico-sdk.git",
            "commit": "4b6e647590213f253f2789ad9026df1d00f38c5d",
            "patches": {
                "foo.patch": ""
            }
        }
        
        """.trimIndent()
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Missing integrity for patch foo.patch of module foo@1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec_whitespacePatchIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"patches\": {",
            "    \"fix.patch\": \"  \"",
            "  }",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Missing integrity for patch fix.patch of module foo@1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec_whitespaceOverlayIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \"sha256-blah\",",
            "  \"overlay\": {",
            "    \"BUILD.bazel\": \"\t\"",
            "  }",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Missing integrity for overlay file BUILD.bazel of module foo@1.0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArchiveRepoSpec_whitespaceIntegrity() {
        server.serve(
            "/modules/foo/1.0/source.json",
            "{",
            "  \"url\": \"http://mysite.com/thing.zip\",",
            "  \"integrity\": \" \"",
            "}"
        )
        server.serve("/modules/foo/1.0/MODULE.bazel", "module(name = \"foo\", version = \"1.0\")")
        server.start()

        val registry: Registry =
            registryFactory.createRegistry(
                server.getUrl(),
                LockfileMode.UPDATE,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                java.util.Optional.empty<T?>(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        val e: IOException? =
            org.junit.Assert.assertThrows<IOException?>(
                IOException::class.java,
                org.junit.function.ThrowingRunnable {
                    registry.getRepoSpec(
                        BzlmodTestUtil.createModuleKey("foo", "1.0"),
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            server.getUrl() + "/modules/foo/1.0/MODULE.bazel",
                            java.util.Optional.of<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(
                                sha256("module(name = \"foo\", version = \"1.0\")")
                            )
                        ),
                        reporter,
                        downloadManager
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("Missing integrity for module foo@1.0")
    }

    companion object {
        @Throws(InvalidChecksumException::class)
        private fun sha256(content: String): com.google.devtools.build.lib.bazel.repository.downloader.Checksum {
            return com.google.devtools.build.lib.bazel.repository.downloader.Checksum.fromString(
                DownloadCache.KeyType.SHA256,
                com.google.common.hash.Hashing.sha256().hashString(content, java.nio.charset.StandardCharsets.UTF_8)
                    .toString()
            )
        }
    }
}
