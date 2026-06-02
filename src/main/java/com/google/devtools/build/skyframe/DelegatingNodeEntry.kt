// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.NodeEntry
import com.google.devtools.build.skyframe.NodeEntry.DependencyState
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.NodeEntry.LifecycleState
import com.google.devtools.build.skyframe.NodeEntry.MarkedDirtyResult
import com.google.devtools.build.skyframe.NodeEntry.NodeValueAndRdepsToSignal
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/** Convenience class for [NodeEntry] implementations that delegate many operations.  */
abstract class DelegatingNodeEntry : NodeEntry {
    protected abstract val delegate: NodeEntry?

    @get:Throws(java.lang.InterruptedException::class)
    val value: SkyValue?
        get() = this.delegate.getValue()

    @get:Throws(java.lang.InterruptedException::class)
    val valueMaybeWithMetadata: SkyValue?
        get() = this.delegate.getValueMaybeWithMetadata()

    @Throws(java.lang.InterruptedException::class)
    override fun toValue(): SkyValue? {
        return this.delegate.toValue()
    }

    @get:Throws(java.lang.InterruptedException::class)
    val errorInfo: com.google.devtools.build.skyframe.ErrorInfo?
        get() = this.delegate.getErrorInfo()

    val inProgressReverseDeps: MutableSet<SkyKey>?
        get() = this.delegate.getInProgressReverseDeps()

    @Throws(java.lang.InterruptedException::class)
    override fun setValue(
        value: SkyValue?,
        graphVersion: com.google.devtools.build.skyframe.Version?,
        maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
    ): MutableSet<SkyKey?>? {
        return this.delegate.setValue(value, graphVersion, maxTransitiveSourceVersion)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun addReverseDepAndCheckIfDone(reverseDep: SkyKey?): DependencyState? {
        return this.delegate.addReverseDepAndCheckIfDone(reverseDep)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun checkIfDoneForDirtyReverseDep(reverseDep: SkyKey?): DependencyState? {
        return this.delegate.checkIfDoneForDirtyReverseDep(reverseDep)
    }

    override fun signalDep(
        childVersion: com.google.devtools.build.skyframe.Version?,
        childForDebugging: SkyKey?
    ): Boolean {
        return this.delegate.signalDep(childVersion, childForDebugging)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun markClean(): NodeValueAndRdepsToSignal? {
        return this.delegate.markClean()
    }

    override fun forceRebuild() {
        this.delegate.forceRebuild()
    }

    val version: com.google.devtools.build.skyframe.Version?
        get() = this.delegate.getVersion()

    val maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?
        get() = this.delegate.getMaxTransitiveSourceVersion()

    override fun setTemporaryMaxTransitiveSourceVersion(maxTransitiveSourceVersion: com.google.devtools.build.skyframe.Version?) {
        this.delegate.setTemporaryMaxTransitiveSourceVersion(maxTransitiveSourceVersion)
    }

    val lifecycleState: LifecycleState?
        get() = this.delegate.getLifecycleState()

    @get:Throws(java.lang.InterruptedException::class)
    val nextDirtyDirectDeps: MutableList<SkyKey>?
        get() = this.delegate.getNextDirtyDirectDeps()

    @get:Throws(java.lang.InterruptedException::class)
    val allDirectDepsForIncompleteNode: com.google.common.collect.ImmutableSet<SkyKey?>?
        get() = this.delegate.getAllDirectDepsForIncompleteNode()

    @get:Throws(java.lang.InterruptedException::class)
    val allRemainingDirtyDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
        get() = this.delegate.getAllRemainingDirtyDirectDeps()

    val allReverseDepsForNodeBeingDeleted: MutableCollection<SkyKey>?
        get() = this.delegate.getAllReverseDepsForNodeBeingDeleted()

    override fun markRebuilding() {
        this.delegate.markRebuilding()
    }

    val temporaryDirectDeps: GroupedDeps?
        get() = this.delegate.getTemporaryDirectDeps()

    override fun noDepsLastBuild(): Boolean {
        return this.delegate.noDepsLastBuild()
    }

    override fun removeUnfinishedDeps(unfinishedDeps: MutableSet<SkyKey?>?) {
        this.delegate.removeUnfinishedDeps(unfinishedDeps)
    }

    override fun resetEvaluationFromScratch() {
        this.delegate.resetEvaluationFromScratch()
    }

    val resetDirectDeps: com.google.common.collect.ImmutableSet<SkyKey?>?
        get() = this.delegate.getResetDirectDeps()

    override fun addSingletonTemporaryDirectDep(dep: SkyKey?) {
        this.delegate.addSingletonTemporaryDirectDep(dep)
    }

    override fun addTemporaryDirectDepGroup(group: MutableList<SkyKey?>?) {
        this.delegate.addTemporaryDirectDepGroup(group)
    }

    override fun addTemporaryDirectDepsInGroups(deps: MutableSet<SkyKey?>?, groupSizes: MutableList<Int?>?) {
        this.delegate.addTemporaryDirectDepsInGroups(deps, groupSizes)
    }

    val isReadyToEvaluate: Boolean
        get() = this.delegate.isReadyToEvaluate()

    override fun hasUnsignaledDeps(): Boolean {
        return this.delegate.hasUnsignaledDeps()
    }

    val isDone: Boolean
        get() = this.delegate.isDone()

    @get:Throws(java.lang.InterruptedException::class)
    val directDeps: Iterable<SkyKey>?
        get() = this.delegate.getDirectDeps()

    @Throws(java.lang.InterruptedException::class)
    override fun hasAtLeastOneDep(): Boolean {
        return this.delegate.hasAtLeastOneDep()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun removeReverseDep(reverseDep: SkyKey?) {
        this.delegate.removeReverseDep(reverseDep)
    }

    override fun removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys: MutableSet<SkyKey?>?) {
        this.delegate.removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys)
    }

    @get:Throws(java.lang.InterruptedException::class)
    val reverseDepsForDoneEntry: MutableCollection<SkyKey>?
        get() = this.delegate.getReverseDepsForDoneEntry()

    val isDirty: Boolean
        get() = this.delegate.isDirty()

    val isChanged: Boolean
        get() = this.delegate.isChanged()

    @Throws(java.lang.InterruptedException::class)
    override fun markDirty(dirtyType: DirtyType?): MarkedDirtyResult? {
        return this.delegate.markDirty(dirtyType)
    }

    override fun addExternalDep() {
        this.delegate.addExternalDep()
    }
}
