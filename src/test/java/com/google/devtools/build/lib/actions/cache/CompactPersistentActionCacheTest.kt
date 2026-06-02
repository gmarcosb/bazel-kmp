// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.cache

import com.google.devtools.build.lib.actions.Artifact

/** Test for the CompactPersistentActionCache class.  */
@RunWith(TestParameterInjector::class)
class CompactPersistentActionCacheTest {
    private val scratch: Scratch = Scratch()
    private var execRoot: Path? = null
    private var cacheRoot: Path? = null
    private var corruptedCacheRoot: Path? = null
    private var tmpDir: Path? = null
    private var mapFile: Path? = null
    private var journalFile: Path? = null
    private var indexFile: Path? = null
    private var indexJournalFile: Path? = null
    private var timestampFile: Path? = null
    private var timestampJournalFile: Path? = null
    private val clock: com.google.devtools.build.lib.testutil.ManualClock =
        com.google.devtools.build.lib.testutil.ManualClock()
    private var cache: CompactPersistentActionCache? = null
    private var artifactRoot: ArtifactRoot? = null

    private val eventHandler: com.google.devtools.build.lib.events.EventHandler? =
        Mockito.spy<com.google.devtools.build.lib.events.EventHandler?>(com.google.devtools.build.lib.events.EventHandler::class.java)

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        execRoot = scratch.resolve("/output")
        cacheRoot = scratch.resolve("/cache_root")
        corruptedCacheRoot = scratch.resolve("/corrupted_cache_root")
        tmpDir = scratch.resolve("/cache_tmp_dir")
        cache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, NullEventHandler.INSTANCE
            )
        mapFile = CompactPersistentActionCache.cacheFile(cacheRoot)
        journalFile = CompactPersistentActionCache.journalFile(cacheRoot)
        indexFile = CompactPersistentActionCache.indexFile(cacheRoot)
        indexJournalFile = CompactPersistentActionCache.indexJournalFile(cacheRoot)
        timestampFile = CompactPersistentActionCache.timestampFile(cacheRoot)
        timestampJournalFile = CompactPersistentActionCache.timestampJournalFile(cacheRoot)
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, ArtifactRoot.RootType.OUTPUT, "bin")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeleteUnrecognizedFiles() {
        val unrecognizedFile: Path = cacheRoot.getRelative("unrecognized")
        FileSystemUtils.createEmptyFile(unrecognizedFile)

        cache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, NullEventHandler.INSTANCE
            )

        assertThat(unrecognizedFile.exists()).isFalse()
    }

    @org.junit.Test
    fun testGetInvalidKey() {
        assertThat(cache.get("key")).isNull()
    }

    @org.junit.Test
    fun testPutAndGet() {
        val key = "key"
        putKey(key)
        val readentry: ActionCache.Entry = cache.get(key)
        assertThat(readentry).isNotNull()
        assertThat(readentry.toString()).isEqualTo(cache.get(key).toString())
        assertThat(mapFile.exists()).isFalse()
    }

    @org.junit.Test
    fun testPutAndRemove() {
        val key = "key"
        putKey(key)
        cache.remove(key)
        assertThat(cache.get(key)).isNull()
        assertThat(mapFile.exists()).isFalse()
    }

    @org.junit.Test
    fun testGetSize() {
        // initial state.
        assertThat(cache.size()).isEqualTo(0)

        val key = "key"
        putKey(key)
        // the inserted key, and the validation key
        assertThat(cache.size()).isEqualTo(2)

        cache.remove(key)
        // the validation key
        assertThat(cache.size()).isEqualTo(1)

        cache.clear()
        assertThat(cache.size()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSaveDiscoverInputs() {
        assertSave(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSaveNoDiscoverInputs() {
        assertSave(false)
    }

    @Throws(java.lang.Exception::class)
    private fun assertSave(discoverInputs: Boolean) {
        val key = "key"
        putKey(key, discoverInputs)
        cache.save()
        assertThat(mapFile.exists()).isTrue()
        assertThat(journalFile.exists()).isFalse()

        val newCache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        val readentry: ActionCache.Entry = newCache.get(key)
        assertThat(readentry).isNotNull()
        assertThat(readentry.toString()).isEqualTo(cache.get(key).toString())
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testIncrementalSave() {
        for (i in 0..299) {
            putKey(java.lang.Integer.toString(i))
        }
        assertFullSave()

        // Add 2 entries to 300. Might as well just leave them in the journal.
        putKey("abc")
        putKey("123")
        assertIncrementalSave(cache)

        // Make sure we have all the entries, including those in the journal,
        // after deserializing into a new cache.
        val newcache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        for (i in 0..99) {
            assertKeyEquals(cache, newcache, java.lang.Integer.toString(i))
        }
        assertKeyEquals(cache, newcache, "abc")
        assertKeyEquals(cache, newcache, "123")
        putKey("xyz", newcache, true)
        assertIncrementalSave(newcache)

        // Make sure we can see previous journal values after a second incremental save.
        val newerCache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        for (i in 0..99) {
            assertKeyEquals(cache, newerCache, java.lang.Integer.toString(i))
        }
        assertKeyEquals(cache, newerCache, "abc")
        assertKeyEquals(cache, newerCache, "123")
        assertThat(newerCache.get("xyz")).isNotNull()
        assertThat(newerCache.get("not_a_key")).isNull()

        // Add another 10 entries. This should not be incremental.
        for (i in 300..309) {
            putKey(java.lang.Integer.toString(i))
        }
        assertFullSave()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testRemoveIf() {
        // Add 100 entries, 5 of which discover inputs, and do a full save.
        for (i in 0..99) {
            putKey(java.lang.Integer.toString(i), i % 20 == 0)
        }
        assertFullSave()

        // Remove entries that discover inputs and flush the journal.
        cache.removeIf(Entry::discoversInputs)
        assertFullSave()

        // Check that the entries that discover inputs are gone, and the rest are still there.
        for (i in 0..99) {
            val entry: ActionCache.Entry? = cache.get(java.lang.Integer.toString(i))
            if (i % 20 == 0) {
                assertThat(entry).isNull()
            } else {
                assertThat(entry).isNotNull()
            }
        }

        // Make sure we get the same result after deserializing into a new cache.
        val newerCache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        for (i in 0..99) {
            val entry: ActionCache.Entry? = newerCache.get(java.lang.Integer.toString(i))
            if (i % 20 == 0) {
                assertThat(entry).isNull()
            } else {
                assertThat(entry).isNotNull()
            }
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testClear() {
        // Add 100 entries and do a full save.
        for (i in 0..99) {
            putKey(java.lang.Integer.toString(i))
        }
        assertFullSave()

        // Clear the cache (which implicitly saves it).
        cache.clear()

        // Check that the cache is empty.
        assertThat(cache.size()).isEqualTo(0)

        // Make sure we get the same result after deserializing into a new cache.
        val newerCache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        assertThat(newerCache.size()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTimestamps() {
        clock.advance(java.time.Duration.ofDays(100))
        putKey("abc")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("def")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("ghi")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("jkl")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("mno",  /* discoversInputs= */true)

        // Getting an entry should update its timestamp.
        clock.advance(java.time.Duration.ofDays(100))
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = cache.get("abc")

        // Overwriting an entry should update its timestamp.
        clock.advance(java.time.Duration.ofDays(100))
        putKey("def")

        // Remove an entry should remove its timestamp.
        clock.advance(java.time.Duration.ofDays(100))
        cache.remove("ghi")

        // Removing entries matching a predicate should not affect the timestamp of other entries.
        clock.advance(java.time.Duration.ofDays(100))
        cache.removeIf(Entry::discoversInputs)

        assertFullSave()

        assertThat(cache.getActionTimestampMap())
            .containsExactly(
                "abc",
                Instant.EPOCH.plus(java.time.Duration.ofDays(600)),
                "def",
                Instant.EPOCH.plus(java.time.Duration.ofDays(700)),
                "jkl",
                Instant.EPOCH.plus(java.time.Duration.ofDays(400))
            )

        // Make sure we get the same result after deserializing into a new cache.
        val newerCache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        assertThat(newerCache.getActionTimestampMap())
            .containsExactly(
                "abc",
                Instant.EPOCH.plus(java.time.Duration.ofDays(600)),
                "def",
                Instant.EPOCH.plus(java.time.Duration.ofDays(700)),
                "jkl",
                Instant.EPOCH.plus(java.time.Duration.ofDays(400))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTrimNoThreshold() {
        clock.advance(java.time.Duration.ofDays(100))
        putKey("abc")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("def")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("ghi")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("jkl")
        clock.advance(java.time.Duration.ofDays(100))
        assertFullSave()

        cache = cache.trim(0, java.time.Duration.ofDays(250))

        // Check that the cache was trimmed correctly.
        assertThat(cache.get("abc")).isNull()
        assertThat(cache.get("def")).isNull()
        assertThat(cache.get("ghi")).isNotNull()
        assertThat(cache.get("jkl")).isNotNull()

        // Make sure we get the same result after deserializing into a new cache.
        val newerCache: CompactPersistentActionCache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )
        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        assertThat(newerCache.get("abc")).isNull()
        assertThat(newerCache.get("def")).isNull()
        assertThat(newerCache.get("ghi")).isNotNull()
        assertThat(newerCache.get("jkl")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTrimBelowThreshold() {
        clock.advance(java.time.Duration.ofDays(100))
        putKey("abc")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("def")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("ghi")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("jkl")
        clock.advance(java.time.Duration.ofDays(100))
        assertFullSave()

        // 1 of 4 entries is stale, below 30% threshold.
        cache = cache.trim(0.3f, java.time.Duration.ofDays(350))

        assertThat(cache.get("abc")).isNotNull()
        assertThat(cache.get("def")).isNotNull()
        assertThat(cache.get("ghi")).isNotNull()
        assertThat(cache.get("jkl")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTrimAboveThreshold() {
        clock.advance(java.time.Duration.ofDays(100))
        putKey("abc")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("def")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("ghi")
        clock.advance(java.time.Duration.ofDays(100))
        putKey("jkl")
        clock.advance(java.time.Duration.ofDays(100))
        assertFullSave()

        // 1 of 4 entries is stale, above 20% threshold.
        cache = cache.trim(0.2f, java.time.Duration.ofDays(350))

        assertThat(cache.get("abc")).isNull()
        assertThat(cache.get("def")).isNotNull()
        assertThat(cache.get("ghi")).isNotNull()
        assertThat(cache.get("jkl")).isNotNull()
    }

    internal enum class IncompatibleFile {
        MAP_FILE,
        JOURNAL_FILE,
        INDEX_FILE,
        INDEX_JOURNAL_FILE,
        TIMESTAMP_FILE,
        TIMESTAMP_JOURNAL_FILE
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testIncompatibleFormat(@TestParameter param: IncompatibleFile) {
        val incompatibleFile: Path? =
            when (param) {
                IncompatibleFile.MAP_FILE -> mapFile
                IncompatibleFile.JOURNAL_FILE -> journalFile
                IncompatibleFile.INDEX_FILE -> indexFile
                IncompatibleFile.INDEX_JOURNAL_FILE -> indexJournalFile
                IncompatibleFile.TIMESTAMP_FILE -> timestampFile
                IncompatibleFile.TIMESTAMP_JOURNAL_FILE -> timestampJournalFile
            }

        FileSystemUtils.writeContent(
            incompatibleFile,
            "incompatible".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)
        )

        cache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )

        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        assertThat(corruptedCacheRoot.exists()).isFalse()
        assertThat(cache.size()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTruncatedMapFile() {
        for (i in 0..299) {
            putKey(java.lang.Integer.toString(i))
        }
        assertFullSave()

        val contents: ByteArray = FileSystemUtils.readContent(mapFile)
        FileSystemUtils.writeContent(mapFile, java.util.Arrays.copyOf(contents, contents.size - 1))

        cache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )

        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler)
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        assertThat(corruptedCacheRoot.exists()).isTrue()
        assertThat(cache.size()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun testTruncatedJournalFile() {
        for (i in 0..299) {
            putKey(java.lang.Integer.toString(i))
        }
        assertFullSave()

        putKey("abc")
        assertIncrementalSave(cache)

        assertThat(cache.size()).isEqualTo(302) // 301 entries + validation record

        val contents: ByteArray = FileSystemUtils.readContent(journalFile)
        FileSystemUtils.writeContent(journalFile, java.util.Arrays.copyOf(contents, contents.size - 1))

        cache =
            CompactPersistentActionCache.create(
                cacheRoot, corruptedCacheRoot, tmpDir, clock, eventHandler
            )

        Mockito.verify<com.google.devtools.build.lib.events.EventHandler?>(eventHandler, Mockito.never())
            .handle(ArgumentMatchers.any<com.google.devtools.build.lib.events.Event?>())
        assertThat(corruptedCacheRoot.exists()).isFalse()
        assertThat(cache.size()).isEqualTo(301)
    }

    @org.junit.Test
    fun putAndGet_savesRemoteFileMetadata() {
        val artifact: Artifact = ActionsTestUtil.Companion.DUMMY_ARTIFACT
        val metadata: FileArtifactValue? = createRemoteMetadata(artifact, "content")
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputFile(artifact, metadata,  /* saveFileMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputFile(artifact)).isEqualTo(metadata)
    }

    @org.junit.Test
    fun putAndGet_savesRemoteFileMetadata_withExpirationTime() {
        val artifact: Artifact = ActionsTestUtil.Companion.DUMMY_ARTIFACT
        val expirationTime: Instant? = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val metadata: FileArtifactValue? =
            createRemoteMetadata(artifact, "content", expirationTime,  /* resolvedPath= */null)
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputFile(artifact, metadata,  /* saveFileMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputFile(artifact).getExpirationTime()).isEqualTo(expirationTime)
    }

    @org.junit.Test
    fun putAndGet_savesRemoteFileMetadata_withResolvedPath() {
        val artifact: Artifact = ActionsTestUtil.Companion.DUMMY_ARTIFACT
        val metadata: FileArtifactValue? =
            createRemoteMetadata(artifact, "content", execRoot.getRelative("some/path").asFragment())
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputFile(artifact, metadata,  /* saveFileMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputFile(artifact)).isEqualTo(metadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun putAndGet_savesProxyOutputs() {
        val artifact: Artifact = ActionsTestUtil.Companion.DUMMY_ARTIFACT
        val metadata: FileArtifactValue =
            ProxyFileArtifactValue(createLocalMetadata(artifact, "content"), artifact.getPath())
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputFile(artifact, metadata,  /* saveFileMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getProxyOutputs()).containsExactly(artifact.getExecPathString())
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun putAndGet_ignoresLocalFileMetadata() {
        val artifact: Artifact = ActionsTestUtil.Companion.DUMMY_ARTIFACT
        val metadata: FileArtifactValue = createLocalMetadata(artifact, "content")
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputFile(artifact, metadata,  /* saveFileMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputFile(artifact)).isNull()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun putAndGet_treeMetadata_onlySavesRemoteFileMetadata() {
        val artifact: SpecialArtifact? =
            createTreeArtifactWithGeneratingAction(
                artifactRoot, PathFragment.create("bin/dummy")
            )
        val metadata: TreeArtifactValue =
            createTreeMetadata(
                artifact,
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                    "file1",
                    createRemoteMetadata(
                        Artifact.TreeFileArtifact.createTreeOutput(
                            artifact, PathFragment.create("file1")
                        ),
                        "content1"
                    ),
                    "file2",
                    createLocalMetadata(
                        Artifact.TreeFileArtifact.createTreeOutput(
                            artifact, PathFragment.create("file2")
                        ),
                        "content2"
                    )
                ),  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputTree(artifact, metadata,  /* saveTreeMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputTree(artifact))
            .isEqualTo(
                SerializableTreeArtifactValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "file1",
                        createRemoteMetadata(
                            TreeFileArtifact.createTreeOutput(artifact, "file1"), "content1"
                        )
                    ),  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
    }

    @org.junit.Test
    fun putAndGet_treeMetadata_savesRemoteArchivedArtifact() {
        val artifact: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(
                artifactRoot, PathFragment.create("bin/dummy")
            )
        val metadata: TreeArtifactValue =
            createTreeMetadata(
                artifact,
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                java.util.Optional.of<FileArtifactValue?>(
                    createRemoteMetadata(
                        artifact,
                        "content"
                    )
                ),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputTree(artifact, metadata,  /* saveTreeMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputTree(artifact))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.of<T?>(createRemoteMetadata(artifact, "content")),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun putAndGet_treeMetadata_ignoresLocalArchivedArtifact() {
        val artifact: SpecialArtifact? =
            createTreeArtifactWithGeneratingAction(
                artifactRoot, PathFragment.create("bin/dummy")
            )
        val metadata: TreeArtifactValue =
            createTreeMetadata(
                artifact,  /* children= */
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                java.util.Optional.of<FileArtifactValue?>(
                    createLocalMetadata(
                        ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/archive"), "content"
                    )
                ),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputTree(artifact, metadata,  /* saveTreeMetadata= */true).build()
        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputTree(artifact).archivedFileValue()).isEmpty()
    }

    @org.junit.Test
    fun putAndGet_treeMetadata_savesResolvedPath() {
        val resolvedPath: PathFragment = execRoot.getRelative("some/path").asFragment()
        val artifact: SpecialArtifact? =
            createTreeArtifactWithGeneratingAction(
                artifactRoot, PathFragment.create("bin/dummy")
            )
        val metadata: TreeArtifactValue =
            createTreeMetadata(
                artifact,
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),
                java.util.Optional.of<PathFragment?>(resolvedPath)
            )
        var entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            builder("key").addOutputTree(artifact, metadata,  /* saveTreeMetadata= */true).build()

        cache.put("key", entry)

        entry = cache.get("key")

        assertThat(entry.getOutputTree(artifact))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),
                    java.util.Optional.of<T?>(resolvedPath)
                )
            )
    }

    @Throws(IOException::class)
    private fun assertFullSave() {
        cache.save()
        assertThat(mapFile.exists()).isTrue()
        assertThat(journalFile.exists()).isFalse()
    }

    @Throws(IOException::class)
    private fun assertIncrementalSave(ac: ActionCache) {
        ac.save()
        assertThat(mapFile.exists()).isTrue()
        assertThat(journalFile.exists()).isTrue()
    }

    private fun putKey(key: String?, discoversInputs: Boolean) {
        putKey(key, cache, discoversInputs)
    }

    companion object {
        @Throws(IOException::class)
        private fun createLocalMetadata(artifact: Artifact, content: String?): FileArtifactValue {
            artifact.getPath().getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContentAsLatin1(artifact.getPath(), content)
            return FileArtifactValue.createForTesting(artifact.getPath())
        }

        private fun createRemoteMetadata(
            artifact: Artifact,
            content: String,
            expirationTime: Instant?,
            resolvedPath: PathFragment?
        ): FileArtifactValue? {
            val bytes: ByteArray = content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            val digest: ByteArray? =
                artifact
                    .getPath()
                    .getFileSystem()
                    .getDigestFunction()
                    .getHashFunction()
                    .hashBytes(bytes)
                    .asBytes()
            var metadata: FileArtifactValue? =
                FileArtifactValue.createForRemoteFileWithMaterializationData(
                    digest, bytes.size, 1, expirationTime
                )
            if (resolvedPath != null) {
                metadata = FileArtifactValue.createFromExistingWithResolvedPath(metadata, resolvedPath)
            }
            return metadata
        }

        private fun createRemoteMetadata(
            artifact: Artifact, content: String, resolvedPath: PathFragment?
        ): FileArtifactValue? {
            return createRemoteMetadata(artifact, content,  /* expirationTime= */null, resolvedPath)
        }

        private fun createRemoteMetadata(artifact: Artifact, content: String): FileArtifactValue? {
            return createRemoteMetadata(artifact, content,  /* resolvedPath= */null)
        }

        private fun createTreeMetadata(
            parent: SpecialArtifact?,
            children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?>,
            archivedArtifactValue: java.util.Optional<FileArtifactValue?>,
            resolvedPath: java.util.Optional<PathFragment?>
        ): TreeArtifactValue {
            val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)
            for (entry in children.entrySet()) {
                builder.putChild(
                    Artifact.TreeFileArtifact.createTreeOutput(parent, entry.getKey()), entry.getValue()
                )
            }
            archivedArtifactValue.ifPresent(
                java.util.function.Consumer { metadata: FileArtifactValue? ->
                    val artifact: ArchivedTreeArtifact? = ArchivedTreeArtifact.createForTree(parent)
                    builder.setArchivedRepresentation(
                        TreeArtifactValue.ArchivedRepresentation.create(artifact, metadata)
                    )
                })
            if (resolvedPath.isPresent()) {
                builder.setResolvedPath(resolvedPath.get())
            }
            return builder.build()
        }

        private fun assertKeyEquals(cache1: ActionCache, cache2: ActionCache, key: String?) {
            val entry: Any? = cache1.get(key)
            Truth.assertThat(entry).isNotNull()
            assertThat(cache2.get(key).toString()).isEqualTo(entry.toString())
        }

        private fun putKey(key: String?, actionCache: ActionCache = cache, discoversInputs: Boolean = false) {
            val entry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                builder(key, discoversInputs).build()
            actionCache.put(key, entry)
        }

        private fun builder(actionKey: String?): ActionCache.Entry.Builder {
            return builder(actionKey,  /* discoversInputs= */false)
        }

        private fun builder(actionKey: String?, discoversInputs: Boolean): ActionCache.Entry.Builder {
            return Builder(
                actionKey,
                discoversInputs,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
                "",
                OutputPermissions.READONLY,  /* useArchivedTreeArtifacts= */
                false
            )
        }
    }
}
