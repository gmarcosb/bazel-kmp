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

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * Tests for [InMemoryNodeEntry] implementations.
 * 
 * 
 * Contains test cases that are relevant to both [IncrementalInMemoryNodeEntry] and [ ]. Test cases that are only partially relevant to one or the other
 * may branch on [InMemoryNodeEntry.keepsEdges] and return early.
 * 
 * @param <V> The type of [Version] used by the [InMemoryNodeEntry] class under test
</V> */
internal abstract class InMemoryNodeEntryTest<V : Version?> {
    @TestParameter
    var isPartialReevaluation: Boolean = false
    protected val initialVersion: V? = getInitialVersion()

    fun createEntry(): InMemoryNodeEntry {
        return createEntry(if (isPartialReevaluation) PARTIAL_REEVALUATION_KEY else REGULAR_KEY)
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun createEntry(key: SkyKey?): InMemoryNodeEntry

    @com.google.errorprone.annotations.ForOverride
    abstract fun getInitialVersion(): V?

    @org.junit.Test
    fun entryAtStartOfEvaluation() {
        val entry: InMemoryNodeEntry = createEntry()
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isDone()).isFalse()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NOT_YET_EVALUATING)
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isDone()).isFalse()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun signalEntry() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val dep1: SkyKey = key("dep1")
        entry.addSingletonTemporaryDirectDep(dep1)
        assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation)
        assertThat(entry.hasUnsignaledDeps()).isTrue()
        assertThat(entry.signalDep(initialVersion, dep1)).isTrue()
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly(dep1)
        val dep2: SkyKey = key("dep2")
        val dep3: SkyKey = key("dep3")
        entry.addSingletonTemporaryDirectDep(dep2)
        entry.addSingletonTemporaryDirectDep(dep3)
        assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation)
        assertThat(entry.hasUnsignaledDeps()).isTrue()
        assertThat(entry.signalDep(initialVersion, dep2)).isFalse()
        assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation)
        assertThat(entry.hasUnsignaledDeps()).isTrue()
        assertThat(entry.signalDep(initialVersion, dep3)).isTrue()
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        Truth.assertThat(setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)).isEmpty()
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.DONE)
        assertThat(entry.getVersion()).isEqualTo(initialVersion)
        if (!entry.keepsEdges()) {
            return
        }
        assertThat(entry.directDeps).containsExactly(dep1, dep2, dep3)
    }

    @org.junit.Test
    fun signalExternalDep() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        entry.addExternalDep()
        assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation)
        assertThat(entry.hasUnsignaledDeps()).isTrue()
        assertThat(entry.signalDep(initialVersion, null)).isTrue()
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        entry.addExternalDep()
        assertThat(entry.isReadyToEvaluate()).isEqualTo(isPartialReevaluation)
        assertThat(entry.hasUnsignaledDeps()).isTrue()
        assertThat(entry.signalDep(initialVersion, null)).isTrue()
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry).hasTemporaryDirectDepsThat().containsExactly()
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun reverseDeps() {
        val entry: InMemoryNodeEntry = createEntry()
        val mother: SkyKey = key("mother")
        val father: SkyKey = key("father")
        assertThat(entry.addReverseDepAndCheckIfDone(mother))
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.addReverseDepAndCheckIfDone(null))
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        assertThat(entry.addReverseDepAndCheckIfDone(father))
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        entry.markRebuilding()
        Truth.assertThat(setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion))
            .containsExactly(mother, father)
        if (!entry.keepsEdges()) {
            return
        }
        assertThat(entry.reverseDepsForDoneEntry).containsExactly(mother, father)
        assertThat(entry.isDone()).isTrue()
        entry.removeReverseDep(mother)
        assertThat(entry.reverseDepsForDoneEntry).doesNotContain(mother)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun errorValue() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val exception: ReifiedSkyFunctionException =
            ReifiedSkyFunctionException(
                GenericFunctionException(SomeErrorException("oops"), Transience.PERSISTENT)
            )
        val errorInfo: ErrorInfo? = ErrorInfo.fromException(exception, false)
        Truth.assertThat(setValue(entry,  /* value= */null, errorInfo, initialVersion)).isEmpty()
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.DONE)
        assertThat(entry.value).isNull()
        assertThat(entry.toValue()).isNull()
        assertThat(entry.errorInfo).isEqualTo(errorInfo)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun errorAndValue() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        val exception: ReifiedSkyFunctionException =
            ReifiedSkyFunctionException(
                GenericFunctionException(SomeErrorException("oops"), Transience.PERSISTENT)
            )
        val errorInfo: ErrorInfo? = ErrorInfo.fromException(exception, false)
        setValue(entry, object : SkyValue() {}, errorInfo, initialVersion)
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.DONE)
        assertThat(entry.errorInfo).isEqualTo(errorInfo)
    }

    @org.junit.Test
    fun crashOnNullErrorAndValue() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                setValue(
                    entry,  /* value= */
                    null,  /* errorInfo= */
                    null,
                    initialVersion
                )
            })
    }

    @org.junit.Test
    fun crashOnTooManySignals() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { entry.signalDep(initialVersion, null) })
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun crashOnSetValueWhenDone() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDone()).isTrue()
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                setValue(
                    entry,
                    object : SkyValue() {},  /* errorInfo= */
                    null,
                    initialVersion
                )
            })
    }

    @org.junit.Test
    fun crashOnAddReverseDepTwice() {
        val entry: InMemoryNodeEntry = createEntry()
        val parent: SkyKey = key("parent")
        assertThat(entry.addReverseDepAndCheckIfDone(parent))
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        entry.addReverseDepAndCheckIfDone(parent)
        entry.markRebuilding()
        val e: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                "Cannot add same dep twice",
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    setValue(
                        entry,
                        object : SkyValue() {},  /* errorInfo= */
                        null,
                        initialVersion
                    )
                })
        Truth.assertThat(e).hasMessageThat().containsMatch("[Dd]uplicate( new)? reverse deps")
    }

    internal class IntegerValue(private val value: Int) : SkyValue {
        override fun equals(that: Any?): Boolean {
            return (that is IntegerValue) && (that.value == value)
        }

        override fun hashCode(): Int {
            return value
        }

        override fun toString(): String {
            return "IntegerValue{" + value + "}"
        }
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null)
        entry.addTemporaryDirectDepsInGroups(
            com.google.common.collect.ImmutableSet.of<E?>(
                key("1A"), key("2A"), key("2B"), key("3A"), key("3B"), key("3C"), key("4A"), key("4B"),
                key("4C"), key("4D")
            ),
            com.google.common.collect.ImmutableList.of<E?>(1, 2, 3, 4)
        )
        assertThat(entry.getTemporaryDirectDeps())
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(key("1A")),
                com.google.common.collect.ImmutableList.of<E?>(key("2A"), key("2B")),
                com.google.common.collect.ImmutableList.of<E?>(key("3A"), key("3B"), key("3C")),
                com.google.common.collect.ImmutableList.of<E?>(key("4A"), key("4B"), key("4C"), key("4D"))
            )
            .inOrder()
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups_toleratesEmpty() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null)
        entry.addTemporaryDirectDepsInGroups(
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>()
        )
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups_toleratesGroupSizeOfZero() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null)
        entry.addTemporaryDirectDepsInGroups(
            com.google.common.collect.ImmutableSet.of<E?>(key("dep")),
            com.google.common.collect.ImmutableList.of<E?>(0, 1, 0)
        )
        assertThat(entry.getTemporaryDirectDeps()).containsExactly(com.google.common.collect.ImmutableList.of<E?>(key("dep")))
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups_notEnoughGroups_throws() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null)
        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                entry.addTemporaryDirectDepsInGroups(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        key("dep")
                    ), com.google.common.collect.ImmutableList.of<E?>()
                )
            })
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups_tooManyGroups_throws() {
        val entry: InMemoryNodeEntry = createEntry()
        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                entry.addTemporaryDirectDepsInGroups(
                    com.google.common.collect.ImmutableSet.of<E?>(),
                    com.google.common.collect.ImmutableList.of<E?>(1)
                )
            })
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups_depsLeftOver_throws() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null)
        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                entry.addTemporaryDirectDepsInGroups(
                    com.google.common.collect.ImmutableSet.of<E?>(key("1"), key("2"), key("3")),
                    com.google.common.collect.ImmutableList.of<E?>(1, 1)
                )
            })
    }

    @org.junit.Test
    fun addTemporaryDirectDepsInGroups_depsExhausted_throws() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null)
        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable {
                entry.addTemporaryDirectDepsInGroups(
                    com.google.common.collect.ImmutableSet.of<E?>(key("1"), key("2"), key("3")),
                    com.google.common.collect.ImmutableList.of<E?>(1, 1, 2)
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetLifecycle() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        // Rdep added before reset.
        val parent1: SkyKey = key("parent1")
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(parent1)
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        // Dep added before reset.
        val dep1: SkyKey = key("dep1")
        entry.addSingletonTemporaryDirectDep(dep1)
        assertThat(entry.signalDep(initialVersion, dep1)).isTrue()
        assertThat(entry.getResetDirectDeps()).isEmpty()
        // Reset clears temporary direct deps.
        entry.resetEvaluationFromScratch()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)
        // Rdep added after reset.
        val parent2: SkyKey = key("parent2")
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(parent2)
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        // Add back same dep.
        entry.addSingletonTemporaryDirectDep(dep1)
        assertThat(entry.signalDep(initialVersion, dep1)).isTrue()
        assertThat(entry.getTemporaryDirectDeps()).containsExactly(com.google.common.collect.ImmutableList.of<E?>(dep1))
        // Dep added after reset.
        val dep2: SkyKey = key("dep2")
        entry.addSingletonTemporaryDirectDep(dep2)
        assertThat(entry.signalDep(initialVersion, dep2)).isTrue()
        assertThat(entry.getTemporaryDirectDeps())
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(dep1),
                com.google.common.collect.ImmutableList.of<E?>(dep2)
            )
        // Deps registered before the reset must be tracked if keeping edges.
        if (entry.keepsEdges()) {
            assertThat(entry.getResetDirectDeps()).containsExactly(dep1)
        }
        // Set value and check that both parents will be signaled.
        Truth.assertThat(
            setValue(
                entry,
                com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1),  /* errorInfo= */
                null,
                initialVersion
            )
        )
            .containsExactly(parent1, parent2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resetTwice_moreDepsRequestedBeforeFirstReset() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        // Rdep added before any reset.
        val parent: SkyKey = key("parent")
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(parent)
            .isEqualTo(DependencyState.ALREADY_EVALUATING)
        // Two deps added before first reset.
        val dep1: SkyKey = key("dep1")
        entry.addSingletonTemporaryDirectDep(dep1)
        assertThat(entry.signalDep(initialVersion, dep1)).isTrue()
        val dep2: SkyKey = key("dep2")
        entry.addSingletonTemporaryDirectDep(dep2)
        assertThat(entry.signalDep(initialVersion, dep2)).isTrue()
        // First reset.
        entry.resetEvaluationFromScratch()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        // Add back only one dep.
        entry.addSingletonTemporaryDirectDep(dep1)
        assertThat(entry.signalDep(initialVersion, dep1)).isTrue()
        assertThat(entry.getTemporaryDirectDeps()).containsExactly(com.google.common.collect.ImmutableList.of<E?>(dep1))
        // Second reset.
        entry.resetEvaluationFromScratch()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        // Both deps added back.
        entry.addSingletonTemporaryDirectDep(dep1)
        assertThat(entry.signalDep(initialVersion, dep1)).isTrue()
        entry.addSingletonTemporaryDirectDep(dep2)
        assertThat(entry.signalDep(initialVersion, dep2)).isTrue()
        assertThat(entry.getTemporaryDirectDeps())
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(dep1),
                com.google.common.collect.ImmutableList.of<E?>(dep2)
            )
        // If tracking of reset deps is required, make sure both deps are reported even though only dep1
        // was registered during the most recent evaluation attempt.
        if (entry.keepsEdges()) {
            assertThat(entry.getResetDirectDeps()).containsExactly(dep1, dep2)
        }
        // Set value and check that parent will be signaled.
        Truth.assertThat(
            setValue(
                entry,
                com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1),  /* errorInfo= */
                null,
                initialVersion
            )
        )
            .containsExactly(parent)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun rewindingLifecycle() {
        val entry: InMemoryNodeEntry = createEntry()
        // Rdep that will eventually rewind the entry.
        val resetParent: SkyKey = key("resetParent")
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(resetParent)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        entry.markRebuilding()

        // Node completes.
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        Truth.assertThat(setValue(entry, oldValue,  /* errorInfo= */null, initialVersion))
            .containsExactly(resetParent)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()

        // Rewinding initiated.
        entry.markDirty(DirtyType.REWIND)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)
        assertThat(entry.toValue()).isEqualTo(oldValue)

        // Parent declares dep again after resetting.
        val dependencyState: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            if (entry.keepsEdges())
                entry.checkIfDoneForDirtyReverseDep(resetParent)
            else
                entry.addReverseDepAndCheckIfDone(resetParent)
        assertThat(dependencyState).isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        entry.markRebuilding()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)

        // Rewound evaluation completes. The parent that initiated rewinding is signalled.
        val newValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(2)
        Truth.assertThat(setValue(entry, newValue,  /* errorInfo= */null, initialVersion))
            .containsExactly(resetParent)
        assertThat(entry.value).isEqualTo(newValue)
        assertThat(entry.toValue()).isEqualTo(newValue)
        assertThat(entry.getVersion()).isEqualTo(initialVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun resetAfterRewind() {
        val entry: InMemoryNodeEntry = createEntry()
        // Rdep that will eventually rewind the entry.
        val resetParent: SkyKey = key("resetParent")
        NodeEntrySubjectFactory.Companion.assertThatNodeEntry(entry)
            .addReverseDepAndCheckIfDone(resetParent)
            .isEqualTo(DependencyState.NEEDS_SCHEDULING)
        entry.markRebuilding()

        // One dep declared.
        val dep: SkyKey = key("dep")
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)

        // Node completes.
        val oldValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(1)
        Truth.assertThat(setValue(entry, oldValue,  /* errorInfo= */null, initialVersion))
            .containsExactly(resetParent)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()

        // Rewinding initiated.
        entry.markDirty(DirtyType.REWIND)
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        Truth.assertThat(entry.getTemporaryDirectDeps() is GroupedDeps.WithHashSet)
            .isEqualTo(isPartialReevaluation)
        assertThat(entry.toValue()).isEqualTo(oldValue)

        // Parent declares dep again after resetting.
        val dependencyState: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            if (entry.keepsEdges())
                entry.checkIfDoneForDirtyReverseDep(resetParent)
            else
                entry.addReverseDepAndCheckIfDone(resetParent)
        assertThat(dependencyState).isEqualTo(DependencyState.NEEDS_SCHEDULING)
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
        assertThat(entry.isReadyToEvaluate()).isTrue()
        assertThat(entry.hasUnsignaledDeps()).isFalse()
        assertThat(entry.getTemporaryDirectDeps()).isEmpty()
        entry.markRebuilding()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.REBUILDING)

        // Dep declared again, then there's a reset.
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        entry.resetEvaluationFromScratch()
        assertThat(entry.toValue()).isEqualTo(oldValue)

        // Dep declared again post-reset.
        entry.addSingletonTemporaryDirectDep(dep)
        entry.signalDep(initialVersion, dep)
        assertThat(entry.toValue()).isEqualTo(oldValue)

        // Rewound evaluation completes. The parent that initiated rewinding is signalled.
        val newValue: SkyValue = com.google.devtools.build.skyframe.InMemoryNodeEntryTest.IntegerValue(2)
        Truth.assertThat(setValue(entry, newValue,  /* errorInfo= */null, initialVersion))
            .containsExactly(resetParent)
        assertThat(entry.value).isEqualTo(newValue)
        assertThat(entry.toValue()).isEqualTo(newValue)
        assertThat(entry.getVersion()).isEqualTo(initialVersion)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun concurrentRewindingAllowed() {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()
        setValue(entry, object : SkyValue() {},  /* errorInfo= */null, initialVersion)
        assertThat(entry.isDirty()).isFalse()
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.markDirty(DirtyType.REWIND)).isNotNull()
        assertThat(entry.markDirty(DirtyType.REWIND)).isNull()
        assertThat(entry.isDirty()).isTrue()
        assertThat(entry.isChanged()).isTrue()
        assertThat(entry.isDone()).isFalse()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.NEEDS_REBUILDING)
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun rewindErrorfulNode_toleratedButNoOp(@TestParameter transience: Transience?) {
        val entry: InMemoryNodeEntry = createEntry()
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation.
        entry.markRebuilding()

        val exception: ReifiedSkyFunctionException =
            ReifiedSkyFunctionException(
                GenericFunctionException(SomeErrorException("oops"), transience)
            )
        val errorInfo: ErrorInfo? = ErrorInfo.fromException(exception, transience === Transience.TRANSIENT)
        Truth.assertThat(setValue(entry,  /* value= */null, errorInfo, initialVersion)).isEmpty()

        assertThat(entry.markDirty(DirtyType.REWIND)).isNull()
        assertThat(entry.isDone()).isTrue()
        assertThat(entry.getLifecycleState()).isEqualTo(LifecycleState.DONE)
        assertThat(entry.value).isNull()
        assertThat(entry.toValue()).isNull()
        assertThat(entry.errorInfo).isEqualTo(errorInfo)
    }

    @org.junit.Test
    fun skipsBatchPrefetch_testTemporaryDepsContainsHashSet() {
        val entry: InMemoryNodeEntry = createEntry(GraphTester.Companion.skipBatchPrefetchKey("dropBatchPrefetch"))
        entry.addReverseDepAndCheckIfDone(null) // Start evaluation
        assertThat(entry.getTemporaryDirectDeps()).isInstanceOf(GroupedDeps.WithHashSet::class.java)
    }

    companion object {
        private val REGULAR_KEY: SkyKey = GraphTester.Companion.skyKey("regular")
        private val PARTIAL_REEVALUATION_KEY: SkyKey = object : SkyKey() {
            public override fun functionName(): SkyFunctionName {
                return SkyFunctionName.FOR_TESTING
            }

            public override fun supportsPartialReevaluation(): Boolean {
                return true
            }
        }
        private val NO_EVENTS: NestedSet<Reportable?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        fun key(name: String?): SkyKey {
            return GraphTester.Companion.skyKey(name)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        fun setValue(
            entry: NodeEntry, value: SkyValue?, errorInfo: ErrorInfo?, graphVersion: Version?
        ): MutableSet<SkyKey?> {
            return entry.setValue(
                ValueWithMetadata.normal(value, errorInfo, NO_EVENTS),
                com.google.common.base.Preconditions.checkNotNull<T?>(graphVersion),  /* maxTransitiveSourceVersion= */
                null
            )
        }
    }
}
