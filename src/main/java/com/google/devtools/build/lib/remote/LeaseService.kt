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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.FileArtifactValue

/** A lease service that manages the lease of remote blobs.  */
class LeaseService(
    memoizingEvaluator: MemoizingEvaluator,
    actionCacheSupplier: java.util.function.Supplier<ActionCache?>,
    leaseExtension: LeaseExtension?
) {
    private val memoizingEvaluator: MemoizingEvaluator
    private val actionCacheSupplier: java.util.function.Supplier<ActionCache?>
    private val leaseExtensionStarted: AtomicBoolean = AtomicBoolean(false)
    var leaseExtension: LeaseExtension?
    private val hasMissingActionInputs: AtomicBoolean = AtomicBoolean(false)

    init {
        this.memoizingEvaluator = memoizingEvaluator
        this.actionCacheSupplier = actionCacheSupplier
        this.leaseExtension = leaseExtension
    }

    fun finalizeAction() {
        if (leaseExtensionStarted.compareAndSet(false, true)) {
            if (leaseExtension != null) {
                leaseExtension!!.start()
            }
        }
    }

    @com.google.common.eventbus.AllowConcurrentEvents
    @com.google.common.eventbus.Subscribe
    fun onLostInputs(event: LostInputsEvent?) {
        hasMissingActionInputs.set(true)
    }

    fun finalizeExecution() {
        if (leaseExtension != null) {
            leaseExtension!!.stop()
        }

        if (hasMissingActionInputs.getAndSet(false)) {
            handleMissingInputs()
        }
    }

    /**
     * An interface whose implementations extend the leases of remote outputs referenced by skyframe.
     */
    interface LeaseExtension {
        fun start()

        fun stop()
    }

    /** Clean up internal state when files are evicted from remote CAS.  */
    private fun handleMissingInputs() {
        // If any outputs are evicted, remove all remote metadata from skyframe and local action cache.
        //
        // With TTL based discarding and lease extension, remote cache eviction error won't happen if
        // remote cache can guarantee the TTL. However, if it happens, it usually means the remote cache
        // is under high load and it could possibly evict more blobs that Bazel wouldn't aware of.
        // Following builds could still fail for the same error (caused by different blobs).
        memoizingEvaluator.delete(
            java.util.function.Predicate { key: SkyKey? ->
                if (key.functionName() == SkyFunctions.ACTION_EXECUTION) {
                    try {
                        val value: SkyValue? = memoizingEvaluator.getExistingValue(key)
                        return@delete value is ActionExecutionValue
                                && isRemote(value)
                    } catch (ignored: java.lang.InterruptedException) {
                        return@delete false
                    }
                }
                false
            })

        val actionCache: ActionCache? = actionCacheSupplier.get()
        if (actionCache != null) {
            actionCache.removeIf(
                { entry -> !entry.getOutputFiles().isEmpty() || !entry.getOutputTrees().isEmpty() })
        }
    }

    private fun isRemote(value: ActionExecutionValue): Boolean {
        return value.getAllFileValues().values().stream().anyMatch(FileArtifactValue::isRemote)
                || value.getAllTreeArtifactValues().values().stream()
            .anyMatch(java.util.function.Predicate { treeArtifactValue: TreeArtifactValue? ->
                this.isRemoteTree(treeArtifactValue)
            })
    }

    private fun isRemoteTree(treeArtifactValue: TreeArtifactValue): Boolean {
        return treeArtifactValue.getChildValues().values().stream()
            .anyMatch(FileArtifactValue::isRemote)
                || treeArtifactValue
            .getArchivedRepresentation()
            .map<Any?>(java.util.function.Function { ar: ArchivedRepresentation? -> ar.archivedFileValue.isRemote() })
            .orElse(false)
    }
}
