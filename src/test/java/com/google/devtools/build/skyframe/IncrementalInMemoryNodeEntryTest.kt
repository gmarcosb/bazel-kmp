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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.NodeEntry.DependencyState

/** Tests for [IncrementalInMemoryNodeEntry].  */
@RunWith(TestParameterInjector::class)
class IncrementalInMemoryNodeEntryTest : InMemoryNodeEntryTest<IntVersion?>() {
    protected val incrementalVersion: IntVersion? = initialVersion.next()

    override fun createEntry(key: SkyKey?): IncrementalInMemoryNodeEntry? {
        return IncrementalInMemoryNodeEntry(key)
    }

    val initialVersion: IntVersion
        get() = IntVersion.of(0)

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun dirtyLifecycle() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        InMemoryNodeEntryTest.Companion.setValue(entry, oldValue,  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()

        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isFalse()
        assertThat(entry.isDone()).isFalse()
        assertThat(entry.toValue()).isEqualTo(oldValue)

        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)

        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        entry.addReverseDepAndCheckIfDone(parent)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)

        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(incrementalVersion, dep)
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()

        entry.markRebuilding()
        Truth.assertThat(
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(2),  /* errorInfo= */
                null,
                incrementalVersion
            )
        )
            .containsExactly(parent)
        assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun changedLifecycle() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        InMemoryNodeEntryTest.Companion.setValue(entry, oldValue,  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()

        entry.markDirty(DirtyType.CHANGE)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        assertThat(entry.toValue()).isEqualTo(oldValue)

        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()

        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        entry.addReverseDepAndCheckIfDone(parent)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()

        entry.markRebuilding()
        Truth.assertThat(
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                object : SkyValue() {},  /* errorInfo= */
                null,
                incrementalVersion
            )
        )
            .containsExactly(parent)
        assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun markDirtyThenChanged() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isFalse()
        assertThat(entry.isDone()).isFalse()
        entry.markDirty(DirtyType.CHANGE)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun markChangedThenDirty() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        entry.markDirty(DirtyType.CHANGE)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun crashOnTwiceMarkedChanged() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        entry.markDirty(DirtyType.CHANGE)
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            "Cannot mark entry changed twice",
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { entry.markDirty(DirtyType.CHANGE) })
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun crashOnTwiceMarkedDirty() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        entry.markDirty(DirtyType.DIRTY)
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            "Cannot mark entry dirty twice",
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { entry.markDirty(DirtyType.DIRTY) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun forceRebuildAfterTransientError() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        entry.addSingletonTemporaryDirectDep(ErrorTransienceValue.KEY)
        entry.signalDep(initialVersion, ErrorTransienceValue.KEY)

        InMemoryNodeEntryTest.Companion.setValue(
            entry,  /* value= */
            null,
            ErrorInfo.fromException(
                ReifiedSkyFunctionException(
                    GenericFunctionException(
                        SomeErrorException("transient error"), Transience.TRANSIENT
                    )
                ),  /* isTransitivelyTransient= */
                true
            ),
            initialVersion
        )
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()

        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isFalse()
        assertThat(entry.isDone()).isFalse()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)

        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(parent)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()

        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        assertThat(entry.signalDep(initialVersion, dep)).isTrue()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)

        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(ErrorTransienceValue.KEY)
        entry.forceRebuild()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)

        Truth.assertThat(
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                object : SkyValue() {},  /* errorInfo= */
                null,
                incrementalVersion
            )
        )
            .containsExactly(parent)
        assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
        assertThat(entry.directDeps).containsExactly(dep) // No more dep on error transience.
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun crashOnAddReverseDepTwiceAfterDone() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        assertThat(entry.addReverseDepAndCheckIfDone(parent)).isEqualTo(DependencyState.DONE)
        entry.addReverseDepAndCheckIfDone(parent)
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            "Cannot add same dep twice",
            java.lang.IllegalStateException::class.java,  // We only check for duplicates when we request all the reverse deps.
            entry::getReverseDepsForDoneEntry
        )
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun crashOnAddReverseDepBeforeAfterDone() {
        val entry: InMemoryNodeEntry = createEntry()
        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        assertThat(entry.addReverseDepAndCheckIfDone(parent))
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        entry.addReverseDepAndCheckIfDone(parent)
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            "Cannot add same dep twice",
            java.lang.IllegalStateException::class.java,  // We only check for duplicates when we request all the reverse deps.
            entry::getReverseDepsForDoneEntry
        )
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun pruneBeforeBuild() {
        val entry: InMemoryNodeEntry = createEntry()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isFalse()
        assertThat(entry.isDone()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        entry.addReverseDepAndCheckIfDone(parent)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion,  /* childForDebugging= */null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.VERIFIED_CLEAN)
        assertThat(entry.markClean().getRdepsToSignal()).containsExactly(parent)
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.getVersion()).isEqualTo(initialVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun pruneAfterBuild() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            initialVersion
        )
        entry.markDirty(DirtyType.DIRTY)
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(incrementalVersion,  /* childForDebugging= */null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep)
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            incrementalVersion
        )
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.getVersion()).isEqualTo(initialVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun noPruneWhenDetailsChange() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            initialVersion
        )
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isFalse()
        assertThat(entry.isDone()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        val parent: SkyKey = InMemoryNodeEntryTest.Companion.key("parent")
        entry.addReverseDepAndCheckIfDone(parent)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(incrementalVersion,  /* childForDebugging= */null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep)
        val exception: ReifiedSkyFunctionException =
            ReifiedSkyFunctionException(
                GenericFunctionException(SomeErrorException("oops"), Transience.PERSISTENT)
            )
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),
            ErrorInfo.fromException(exception, false),
            incrementalVersion
        )
        assertThat(entry.isDone()).isTrue()
        Truth.assertWithMessage("Version increments when setValue changes")
            .that(entry.getVersion())
            .isEqualTo(IntVersion.of(1))
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun pruneWhenDepGroupReordered() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        val dep1InGroup: SkyKey = InMemoryNodeEntryTest.Companion.key("dep1InGroup")
        val dep2InGroup: SkyKey = InMemoryNodeEntryTest.Companion.key("dep2InGroup")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>(dep1InGroup, dep2InGroup))
        entry.signalDep(initialVersion, dep)
        entry.signalDep(initialVersion, dep1InGroup)
        entry.signalDep(initialVersion, dep2InGroup)
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            initialVersion
        )
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        entry.markDirty(DirtyType.DIRTY)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isFalse()
        assertThat(entry.isDone()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        entry.addReverseDepAndCheckIfDone(null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(incrementalVersion,  /* childForDebugging= */null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep)
        entry.markRebuilding()
        entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>(dep2InGroup, dep1InGroup))
        assertThat(entry.signalDep(incrementalVersion, dep2InGroup)).isFalse()
        assertThat(entry.signalDep(incrementalVersion, dep1InGroup)).isTrue()
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            incrementalVersion
        )
        assertThat(entry.isDone()).isTrue()
        Truth.assertWithMessage("Version does not change when dep group reordered")
            .that(entry.getVersion())
            .isEqualTo(IntVersion.of(0))
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun errorInfoCannotBePruned() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        val exception: ReifiedSkyFunctionException =
            ReifiedSkyFunctionException(
                GenericFunctionException(SomeErrorException("oops"), Transience.PERSISTENT)
            )
        val errorInfo: ErrorInfo? = ErrorInfo.fromException(exception, false)
        InMemoryNodeEntryTest.Companion.setValue(entry,  /* value= */null, errorInfo, initialVersion)
        entry.markDirty(DirtyType.DIRTY)
        entry.addReverseDepAndCheckIfDone(null) // Restart evaluation.
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(incrementalVersion,  /* childForDebugging= */null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep)
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(entry,  /* value= */null, errorInfo, incrementalVersion)
        assertThat(entry.isDone()).isTrue()
        // ErrorInfo is treated as a NotComparableSkyValue, so it is not pruned.
        assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
    }

    @get:Throws(java.lang.InterruptedException::class)
    @get:org.junit.Test
    val dependencyGroup: Unit
        get() {
            val entry: InMemoryNodeEntry = createEntry()
            entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
            entry.markRebuilding()
            val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
            val dep2: SkyKey = InMemoryNodeEntryTest.Companion.key("dep2")
            val dep3: SkyKey = InMemoryNodeEntryTest.Companion.key("dep3")
            entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>(dep, dep2))
            entry.addSingletonTemporaryDirectDep(dep3)
            entry.signalDep(initialVersion, dep)
            entry.signalDep(initialVersion, dep2)
            entry.signalDep(initialVersion, dep3)
            InMemoryNodeEntryTest.Companion.setValue(
                entry,  /* value= */
                com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),
                null,
                initialVersion
            )
            entry.markDirty(DirtyType.DIRTY)
            entry.addReverseDepAndCheckIfDone(null) // Restart evaluation.
            assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
            assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep, dep2)
            entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>(dep, dep2))
            entry.signalDep(initialVersion,  /* childForDebugging= */null)
            entry.signalDep(initialVersion,  /* childForDebugging= */null)
            assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
            assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep3)
        }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun maintainDependencyGroupAfterRemoval() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        val dep2: SkyKey = InMemoryNodeEntryTest.Companion.key("dep2")
        val dep3: SkyKey = InMemoryNodeEntryTest.Companion.key("dep3")
        val dep4: SkyKey = InMemoryNodeEntryTest.Companion.key("dep4")
        val dep5: SkyKey = InMemoryNodeEntryTest.Companion.key("dep5")
        entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>(dep, dep2, dep3))
        entry.addSingletonTemporaryDirectDep(dep4)
        entry.addSingletonTemporaryDirectDep(dep5)
        entry.signalDep(initialVersion, dep4)
        entry.signalDep(initialVersion, dep)
        // Oops! Evaluation terminated with an error, but we're going to set this entry's value anyway.
        entry.removeUnfinishedDeps(com.google.common.collect.ImmutableSet.of<E?>(dep2, dep3, dep5))
        val exception: ReifiedSkyFunctionException =
            ReifiedSkyFunctionException(
                GenericFunctionException(SomeErrorException("oops"), Transience.PERSISTENT)
            )
        InMemoryNodeEntryTest.Companion.setValue(entry, null, ErrorInfo.fromException(exception, false), initialVersion)
        entry.markDirty(DirtyType.DIRTY)
        entry.addReverseDepAndCheckIfDone(null) // Restart evaluation.
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep4)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun pruneWhenDepsChange() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            initialVersion
        )
        entry.markDirty(DirtyType.DIRTY)
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(dep)
        entry.addSingletonTemporaryDirectDep(dep)
        assertThat(entry.signalDep(incrementalVersion,  /* childForDebugging= */null)).isTrue()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep)
        entry.markRebuilding()
        entry.addSingletonTemporaryDirectDep(InMemoryNodeEntryTest.Companion.key("dep2"))
        assertThat(entry.signalDep(incrementalVersion,  /* childForDebugging= */null)).isTrue()
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            incrementalVersion
        )
        assertThat(entry.isDone()).isTrue()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasVersionThat().isEqualTo(initialVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun checkDepsOneByOne() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val deps: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (ii in 0..9) {
            val dep: SkyKey = InMemoryNodeEntryTest.Companion.key(ii.toString())
            deps.add(dep)
            entry.addSingletonTemporaryDirectDep(dep)
            entry.signalDep(initialVersion, dep)
        }
        InMemoryNodeEntryTest.Companion.setValue(
            entry,
            com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(5),  /* errorInfo= */
            null,
            initialVersion
        )
        entry.markDirty(DirtyType.DIRTY)
        entry.addReverseDepAndCheckIfDone(null) // Start new evaluation.
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
        for (ii in 0..9) {
            assertThat(entry.getNextDirtyDirectDeps()).containsExactly(deps.get(ii))
            entry.addSingletonTemporaryDirectDep(deps.get(ii))
            assertThat(entry.signalDep(initialVersion,  /* childForDebugging= */null)).isTrue()
            if (ii < 9) {
                assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)
            } else {
                assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.VERIFIED_CLEAN)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun signalOnlyNewParents() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(InMemoryNodeEntryTest.Companion.key("parent"))
        entry.markRebuilding()
        InMemoryNodeEntryTest.Companion.setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        entry.markDirty(DirtyType.CHANGE)
        val newParent: SkyKey = InMemoryNodeEntryTest.Companion.key("new parent")
        entry.addReverseDepAndCheckIfDone(newParent)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        entry.markRebuilding()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)
        Truth.assertThat(
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                object : SkyValue() {},  /* errorInfo= */
                null,
                incrementalVersion
            )
        )
            .containsExactly(newParent)
    }

    @get:Throws(java.lang.InterruptedException::class)
    @get:org.junit.Test
    val compressedDirectDepsForDoneEntry: Unit
        get() {
            val entry: InMemoryNodeEntry = createEntry()
            val groupedDirectDeps: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<SkyKey?>> =
                com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<SkyKey?>?>(
                    com.google.common.collect.ImmutableList.of<SkyKey?>(InMemoryNodeEntryTest.Companion.key("1A")),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(
                        InMemoryNodeEntryTest.Companion.key("2A"),
                        InMemoryNodeEntryTest.Companion.key("2B")
                    ),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(
                        InMemoryNodeEntryTest.Companion.key("3A"),
                        InMemoryNodeEntryTest.Companion.key("3B"),
                        InMemoryNodeEntryTest.Companion.key("3C")
                    ),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(
                        InMemoryNodeEntryTest.Companion.key("4A"),
                        InMemoryNodeEntryTest.Companion.key("4B"),
                        InMemoryNodeEntryTest.Companion.key("4C"),
                        InMemoryNodeEntryTest.Companion.key("4D")
                    )
                )
            NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
                .addReverseDepAndCheckIfDone(null)
                .isEqualTo(DependencyState.NEEDS_SCHEDULING)
            entry.markRebuilding()
            for (depGroup in groupedDirectDeps) {
                entry.addTemporaryDirectDepGroup(depGroup)
                for (dep in depGroup) {
                    entry.signalDep(initialVersion, dep)
                }
            }
            entry.setValue(
                com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(42),
                IntVersion.of(42L),
                null
            )
            assertThat(GroupedDeps.decompress(entry.compressedDirectDepsForDoneEntry))
                .containsExactlyElementsIn(groupedDirectDeps)
                .inOrder()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasAtLeastOneDep_true() {
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        val entry: InMemoryNodeEntry = createEntry()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        entry.markRebuilding()
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        entry.setValue(com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1), initialVersion, null)
        assertThat(entry.hasAtLeastOneDep()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasAtLeastOneDep_false() {
        val entry: InMemoryNodeEntry = createEntry()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(null)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        entry.markRebuilding()
        entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>())
        entry.setValue(com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1), initialVersion, null)
        assertThat(entry.hasAtLeastOneDep()).isFalse()
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val allDirectDepsForIncompleteNode_notYetEvaluating: Unit
        get() {
            val entry: InMemoryNodeEntry = createEntry()
            assertThat(entry.getAllDirectDepsForIncompleteNode()).isEmpty()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val allDirectDepsForIncompleteNode_initialBuild: Unit
        get() {
            val entry: InMemoryNodeEntry = createEntry()
            entry.addReverseDepAndCheckIfDone(null)
            entry.markRebuilding()

            val dep1: SkyKey = InMemoryNodeEntryTest.Companion.key("dep1")
            val dep2: SkyKey = InMemoryNodeEntryTest.Companion.key("dep2")
            entry.addTemporaryDirectDepGroup(com.google.common.collect.ImmutableList.of<E?>(dep1, dep2))

            assertThat(entry.getAllDirectDepsForIncompleteNode()).containsExactly(dep1, dep2)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val allDirectDepsForIncompleteNode_incrementalBuild: Unit
        get() {
            val entry: InMemoryNodeEntry = createEntry()
            entry.addReverseDepAndCheckIfDone(null)
            entry.markRebuilding()

            // Dep added on initial build that stays on incremental build.
            val oldAndNewDep: SkyKey = InMemoryNodeEntryTest.Companion.key("oldAndNewDep")
            entry.addSingletonTemporaryDirectDep(oldAndNewDep)
            entry.signalDep(initialVersion, oldAndNewDep)

            // Dep added on initial build that is removed on incremental build.
            val oldDep: SkyKey = InMemoryNodeEntryTest.Companion.key("oldDep")
            entry.addSingletonTemporaryDirectDep(oldDep)
            entry.signalDep(initialVersion, oldDep)

            // Initial build completes.
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1),  /* errorInfo= */
                null,
                initialVersion
            )

            // Start of incremental build.
            entry.markDirty(DirtyType.DIRTY)
            entry.addReverseDepAndCheckIfDone(null)

            // First dep changed, causes rebuild.
            assertThat(entry.getNextDirtyDirectDeps()).containsExactly(oldAndNewDep)
            entry.addSingletonTemporaryDirectDep(oldAndNewDep)
            entry.signalDep(incrementalVersion, oldAndNewDep)
            entry.markRebuilding()

            // New dep requested.
            val newDep: SkyKey = InMemoryNodeEntryTest.Companion.key("newDep")
            entry.addSingletonTemporaryDirectDep(newDep)

            assertThat(entry.getAllDirectDepsForIncompleteNode())
                .containsExactly(oldDep, oldAndNewDep, newDep)
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val allDirectDepsForIncompleteNode_afterReset: Unit
        get() {
            val entry: InMemoryNodeEntry = createEntry()
            entry.addReverseDepAndCheckIfDone(null)
            entry.markRebuilding()

            val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
            entry.addSingletonTemporaryDirectDep(dep)
            entry.signalDep(initialVersion, dep)
            entry.resetEvaluationFromScratch()

            assertThat(entry.getAllDirectDepsForIncompleteNode()).containsExactly(dep)
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetOnDirtyNode(@TestParameter valueChanges: Boolean) {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()

        // Rdep added on initial build that stays on incremental build.
        val oldAndNewParent: SkyKey = InMemoryNodeEntryTest.Companion.key("oldAndNewParent")
        entry.addReverseDepAndCheckIfDone(oldAndNewParent)

        // Rdep added on initial build that is removed on incremental build.
        val oldParent: SkyKey = InMemoryNodeEntryTest.Companion.key("oldParent")
        entry.addReverseDepAndCheckIfDone(oldParent)

        // Dep added on initial build that stays on incremental build.
        val oldAndNewDep: SkyKey = InMemoryNodeEntryTest.Companion.key("oldAndNewDep")
        entry.addSingletonTemporaryDirectDep(oldAndNewDep)
        entry.signalDep(initialVersion, oldAndNewDep)

        // Dep added on initial build that is removed on incremental build.
        val oldDep: SkyKey = InMemoryNodeEntryTest.Companion.key("oldDep")
        entry.addSingletonTemporaryDirectDep(oldDep)
        entry.signalDep(initialVersion, oldDep)

        // Initial build completes.
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        InMemoryNodeEntryTest.Companion.setValue(entry, oldValue,  /* errorInfo= */null, initialVersion)

        // Start of incremental build.
        entry.markDirty(DirtyType.DIRTY)

        // One rdep added, one rdep stays, one rdep removed.
        val newParent: SkyKey = InMemoryNodeEntryTest.Companion.key("newParent")
        entry.addReverseDepAndCheckIfDone(newParent)
        entry.checkIfDoneForDirtyReverseDep(oldAndNewParent)
        entry.removeReverseDep(oldParent)

        // Old dep added before reset, triggers rebuild.
        assertThat(entry.getNextDirtyDirectDeps()).containsExactly(oldAndNewDep)
        entry.addSingletonTemporaryDirectDep(oldAndNewDep)
        entry.signalDep(incrementalVersion, oldAndNewDep)
        entry.markRebuilding()
        assertThat(entry.getResetDirectDeps()).isEmpty()

        // Reset clears temporary direct deps, but preserves the dirty value.
        entry.resetEvaluationFromScratch()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)
        assertThat(entry.toValue()).isEqualTo(oldValue)

        // Add back same dep.
        entry.addSingletonTemporaryDirectDep(oldAndNewDep)
        assertThat(entry.signalDep(incrementalVersion, oldAndNewDep)).isTrue()
        assertThat(entry.getTemporaryDirectDeps()).containsExactly(
            com.google.common.collect.ImmutableList.of<E?>(
                oldAndNewDep
            )
        )

        // New dep added after reset.
        val newDep: SkyKey = InMemoryNodeEntryTest.Companion.key("newDep")
        entry.addSingletonTemporaryDirectDep(newDep)
        assertThat(entry.signalDep(incrementalVersion, newDep)).isTrue()
        assertThat(entry.getTemporaryDirectDeps())
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(oldAndNewDep),
                com.google.common.collect.ImmutableList.of<E?>(newDep)
            )

        // Check dep accounting.
        assertThat(entry.getResetDirectDeps()).containsExactly(oldAndNewDep)
        assertThat(entry.getAllRemainingDirtyDirectDeps()).containsExactly(oldDep)

        // Set value and check that new parents will be signaled.
        val newValue: SkyValue =
            if (valueChanges) com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(2) else oldValue
        Truth.assertThat(
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                newValue,  /* errorInfo= */
                null,
                incrementalVersion
            )
        )
            .containsExactly(oldAndNewParent, newParent)

        if (valueChanges) {
            assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
        } else {
            assertThat(entry.getVersion()).isEqualTo(initialVersion) // Change pruning.
        }
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun rewindOnIncrementalBuild(@TestParameter valueChanges: Boolean) {
        val entry: InMemoryNodeEntry = createEntry()

        // Initial build.
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        InMemoryNodeEntryTest.Companion.setValue(entry, oldValue,  /* errorInfo= */null, initialVersion)

        // Rdeps that exhibit various behavior and timing on the incremental build.
        val earlyOldParent: SkyKey = InMemoryNodeEntryTest.Companion.key("earlyOldParent")
        val lateOldParent: SkyKey = InMemoryNodeEntryTest.Companion.key("lateOldParent")
        val cleanParent: SkyKey = InMemoryNodeEntryTest.Companion.key("cleanParent")
        val resetDirtyParent: SkyKey = InMemoryNodeEntryTest.Companion.key("resetDirtyParent")
        val earlyDirtyParent: SkyKey = InMemoryNodeEntryTest.Companion.key("earlyDirtyParent")
        val lateDirtyParent: SkyKey = InMemoryNodeEntryTest.Companion.key("lateDirtyParent")
        entry.addReverseDepAndCheckIfDone(earlyOldParent)
        entry.addReverseDepAndCheckIfDone(lateOldParent)
        entry.addReverseDepAndCheckIfDone(cleanParent)
        entry.addReverseDepAndCheckIfDone(resetDirtyParent)
        entry.addReverseDepAndCheckIfDone(earlyDirtyParent)
        entry.addReverseDepAndCheckIfDone(lateDirtyParent)

        // Start of incremental build.

        // Rdep removed before rewinding.
        entry.removeReverseDep(earlyOldParent)

        // Dirty rdep registered before rewinding.
        assertThat(entry.checkIfDoneForDirtyReverseDep(earlyDirtyParent))
            .isEqualTo(DependencyState.DONE)

        // New rdep registered before rewinding.
        val earlyNewParent: SkyKey = InMemoryNodeEntryTest.Companion.key("earlyNewParent")
        assertThat(entry.addReverseDepAndCheckIfDone(earlyNewParent)).isEqualTo(DependencyState.DONE)

        // Rewinding initiated.
        assertThat(entry.checkIfDoneForDirtyReverseDep(resetDirtyParent))
            .isEqualTo(DependencyState.DONE)
        assertThat(entry.markDirty(DirtyType.REWIND)).isNotNull()
        assertThat(entry.toValue()).isEqualTo(oldValue)

        // Parent declares dep again after resetting.
        assertThat(entry.checkIfDoneForDirtyReverseDep(resetDirtyParent))
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        entry.markRebuilding()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)

        // Rdep removed after rewinding was initiated.
        entry.removeReverseDep(lateOldParent)

        // Dirty rdep registered after rewinding was initiated.
        assertThat(entry.checkIfDoneForDirtyReverseDep(lateDirtyParent))
            .isEqualTo(DependencyState.ALREADY_EVALUATING)

        // New rdep registered after rewinding was initiated.
        val lateNewParent: SkyKey = InMemoryNodeEntryTest.Companion.key("lateNewParent")
        assertThat(entry.addReverseDepAndCheckIfDone(lateNewParent))
            .isEqualTo(DependencyState.ALREADY_EVALUATING)

        // Rewound evaluation completes. Only parents that are waiting on the node (registered after
        // rewinding was initiated) are signalled.
        val newValue: SkyValue =
            if (valueChanges) com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(2) else oldValue
        Truth.assertThat(
            InMemoryNodeEntryTest.Companion.setValue(
                entry,
                newValue,  /* errorInfo= */
                null,
                incrementalVersion
            )
        )
            .containsExactly(resetDirtyParent, lateDirtyParent, lateNewParent)
        assertThat(entry.value).isEqualTo(newValue)
        if (valueChanges) {
            assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
        } else {
            assertThat(entry.getVersion()).isEqualTo(initialVersion) // Change pruning.
        }

        // Check rdep accounting.
        assertThat(entry.reverseDepsForDoneEntry)
            .containsExactly(
                cleanParent,
                earlyDirtyParent,
                earlyNewParent,
                resetDirtyParent,
                lateDirtyParent,
                lateNewParent
            )
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun rewindOnDirtyNodeIgnored(@TestParameter valueChanges: Boolean) {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()

        // Dep added on initial build that stays on incremental build.
        val dep: SkyKey = InMemoryNodeEntryTest.Companion.key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)

        // Initial build completes.
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        InMemoryNodeEntryTest.Companion.setValue(entry, oldValue,  /* errorInfo= */null, initialVersion)

        // Start of incremental build.
        entry.markDirty(DirtyType.DIRTY)
        entry.addReverseDepAndCheckIfDone(null)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)

        // Attempt to rewind node while it is dirty. Its lifecycle state does not change.
        entry.markDirty(DirtyType.REWIND)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.CHECK_DEPENDENCIES)

        // Add back same dep.
        entry.addSingletonTemporaryDirectDep(dep)
        assertThat(entry.signalDep(incrementalVersion, dep)).isTrue()
        entry.markRebuilding()
        assertThat(entry.getTemporaryDirectDeps()).containsExactly(com.google.common.collect.ImmutableList.of<E?>(dep))

        // Set value and check version.
        val newValue: SkyValue =
            if (valueChanges) com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(2) else oldValue
        InMemoryNodeEntryTest.Companion.setValue(entry, newValue,  /* errorInfo= */null, incrementalVersion)

        if (valueChanges) {
            assertThat(entry.getVersion()).isEqualTo(incrementalVersion)
        } else {
            assertThat(entry.getVersion()).isEqualTo(initialVersion) // Change pruning.
        }
    }
}
