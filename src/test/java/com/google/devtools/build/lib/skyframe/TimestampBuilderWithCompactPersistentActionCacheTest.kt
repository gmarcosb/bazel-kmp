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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Artifact

/**
 * These tests are similar to [TimestampBuilderTest], but uses [ ] in the builder.
 */
@RunWith(JUnit4::class)
class TimestampBuilderWithCompactPersistentActionCacheTest : TimestampBuilderTestCase() {
    private val storedEventHandler: StoredEventHandler = StoredEventHandler()
    private var cacheRoot: Path? = null
    private var corruptedCacheRoot: Path? = null
    private var tmpDir: Path? = null
    private var cache: CompactPersistentActionCache? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setCache() {
        // BlazeRuntime.setupLogging(Level.FINEST);  // Uncomment this for debugging.

        cacheRoot = scratch.dir("cacheRoot")
        corruptedCacheRoot = scratch.dir("corruptedCacheRoot")
        tmpDir = scratch.dir("cacheTmp")
        cache = createCache()
    }

    @Throws(IOException::class)
    private fun createCache(): CompactPersistentActionCache {
        return CompactPersistentActionCache.create(
            cacheRoot, corruptedCacheRoot, tmpDir, clock, storedEventHandler
        )
    }

    /**
     * Creates and returns a new caching builder based on a given `cache`.
     */
    @Throws(java.lang.Exception::class)
    private fun persistentBuilder(cache: CompactPersistentActionCache?): Builder? {
        return createBuilder(cache)
    }

    // TODO(blaze-team): (2009) :
    // - test timestamp monotonicity is not required (i.e. set mtime backwards)
    // - test change of key causes rebuild
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnneededInputs() {
        val hello: Artifact = createSourceArtifact("hello")
        hello.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content1")
        val optional: Artifact = createSourceArtifact("hello.optional")
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello, optional), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Creating a new persistent cache does not cause a rebuild
        cache.save()
        cache = createCache()
        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        BlazeTestUtils.makeEmptyFile(optional.getPath())
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content2")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        optional.getPath().delete()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content3")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        // Creating a new persistent cache does not cause a rebuild
        cache.save()
        cache = createCache()
        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPersistentCache_modifyingInputCausesActionReexecution() {
        // /hello -> [action] -> /goodbye
        val hello: Artifact = createSourceArtifact("hello")
        BlazeTestUtils.makeEmptyFile(hello.getPath())
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Creating a new persistent cache does not cause a rebuild
        cache.save()
        buildArtifacts(persistentBuilder(createCache()), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifyingInputCausesActionReexecution() {
        // /hello -> [action] -> /goodbye
        val hello: Artifact = createSourceArtifact("hello")
        hello.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content1")
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // still not rebuilt

        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content2")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Creating a new persistent cache does not cause a rebuild
        cache.save()
        buildArtifacts(persistentBuilder(createCache()), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactOrderingDoesNotMatter() {
        // (/hello,/there) -> [action] -> /goodbye

        val hello: Artifact = createSourceArtifact("hello")
        val there: Artifact = createSourceArtifact("there")
        hello.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "hello")
        FileSystemUtils.writeContentAsLatin1(there.getPath(), "there")
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello, there), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Now create duplicate graph, with swapped order.
        clearActions()
        val goodbye2: Artifact = createDerivedArtifact("goodbye")
        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(there, hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye2)
        )

        button2.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button2.pressed).isFalse() // still not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOldCacheKeysAreCleanedUp() {
        // [action1] -> (/goodbye), cache key will be /goodbye
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        goodbye.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(goodbye.getPath(), "test")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        // action1 is cached using the cache key /goodbye.
        assertThat(cache.get(goodbye.getExecPathString())).isNotNull()

        // [action2] -> (/hello,/goodbye), cache key will be /hello
        clearActions()
        val hello: Artifact = createDerivedArtifact("hello")
        val goodbye2: Artifact = createDerivedArtifact("goodbye")
        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello, goodbye2)
        )

        button2.pressed = false
        buildArtifacts(persistentBuilder(cache), hello, goodbye2)
        Truth.assertThat(button2.pressed).isTrue() // rebuilt

        // action2 is cached using the cache key /hello.
        assertThat(cache.get(hello.getExecPathString())).isNotNull()

        // Now, action1 should no longer be in the cache.
        assertThat(cache.get(goodbye.getExecPathString())).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactNamesMatter() {
        // /hello -> [action] -> /goodbye

        val hello: Artifact = createSourceArtifact("hello")
        hello.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "hello")
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Now create duplicate graph, replacing "hello" with "hi".
        clearActions()
        val hi: Artifact = createSourceArtifact("hi")
        hi.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hi.getPath(), "hello")
        val goodbye2: Artifact = createDerivedArtifact("goodbye")
        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hi), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye2)
        )

        button2.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye2)
        Truth.assertThat(button2.pressed).isTrue() // name changed. must rebuild.
    }

    /**
     * Tests that changing timestamp of the input file without changing it content
     * does not cause action reexecution when metadata cache uses file digests in
     * addition to the timestamp.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModifyingTimestampOnlyDoesNotCauseActionReexecution() {
        // /hello -> [action] -> /goodbye
        val hello: Artifact = createSourceArtifact("hello")
        hello.getPath().getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "content1")
        val goodbye: Artifact = createDerivedArtifact("goodbye")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            asNestedSet(hello), com.google.common.collect.ImmutableSet.of<Artifact?>(goodbye)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Creating a new persistent caches, including metadata cache does not cause
        // a rebuild
        cache.save()
        val builder: Builder? = persistentBuilder(createCache())
        buildArtifacts(builder, goodbye)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPersistentCache_modifyingOutputCausesActionReexecution() {
        // [action] -> /hello
        val hello: Artifact = createDerivedArtifact("hello")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Creating a new persistent cache does not cause a rebuild
        cache.save()
        buildArtifacts(persistentBuilder(createCache()), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPersistentCache_missingFilenameIndexCausesActionReexecution() {
        // [action] -> /hello
        val hello: Artifact = createDerivedArtifact("hello")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Creating a new persistent cache does not cause a rebuild
        cache.save()

        // Remove filename index file.
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                Builder(cacheRoot, FilesystemOps.DIRECT)
                    .addPattern("filename_index*")
                    .globInterruptible()
            )
                .delete()
        )
            .isTrue()

        // Now first cache creation attempt should cause IOException while renaming corrupted files.
        // Second attempt will initialize empty cache, causing rebuild.
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()
        buildArtifacts(persistentBuilder(createCache()), hello)
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .contains("Failed action cache referential integrity check")

        Truth.assertThat(button.pressed).isTrue() // rebuilt due to the missing filename index
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPersistentCache_failedIntegrityCheckCausesActionReexecution() {
        // [action] -> /hello
        val hello: Artifact = createDerivedArtifact("hello")
        val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(hello)
        )

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isTrue() // built

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        hello.getPath().setWritable(true)
        FileSystemUtils.writeContentAsLatin1(hello.getPath(), "new content")

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isTrue() // rebuilt

        button.pressed = false
        buildArtifacts(persistentBuilder(cache), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        cache.save()

        // Get filename index path and store a copy of it.
        val indexPath: Path? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                Builder(cacheRoot, FilesystemOps.DIRECT)
                    .addPattern("filename_index*")
                    .globInterruptible()
            )
        val indexCopy: Path = scratch.resolve("index_copy")
        FileSystemUtils.copyFile(indexPath, indexCopy)

        // Add extra records to the action cache and indexer.
        val helloExtra: Artifact = createDerivedArtifact("hello_extra")
        val buttonExtra: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button = createActionButton(
            TimestampBuilderTestCase.Companion.emptyNestedSet,
            com.google.common.collect.ImmutableSet.of<Artifact?>(helloExtra)
        )
        buildArtifacts(persistentBuilder(cache), helloExtra)
        Truth.assertThat(buttonExtra.pressed).isTrue() // built

        cache.save()
        assertThat(indexPath.getFileSize()).isGreaterThan(indexCopy.getFileSize())

        // Validate current cache.
        buildArtifacts(persistentBuilder(createCache()), hello)
        Truth.assertThat(button.pressed).isFalse() // not rebuilt

        // Restore outdated file index.
        FileSystemUtils.copyFile(indexCopy, indexPath)

        // Now first cache creation attempt should cause IOException while renaming corrupted files.
        // Second attempt will initialize empty cache, causing rebuild.
        Truth.assertThat(storedEventHandler.getEvents()).isEmpty()
        buildArtifacts(persistentBuilder(createCache()), hello)
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getEvents().get(0).getMessage())
            .contains("Failed action cache referential integrity check")

        Truth.assertThat(button.pressed).isTrue() // rebuilt due to the out-of-date index
    }

    companion object {
        private fun asNestedSet(vararg artifacts: Artifact?): NestedSet<Artifact?> {
            return NestedSetBuilder.create(Order.STABLE_ORDER, artifacts)
        }
    }
}
