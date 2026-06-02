// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common

import com.google.devtools.build.lib.actions.ActionInput

/**
 * Exception which represents a collection of IOExceptions for the purpose of distinguishing remote
 * communication exceptions from those which occur on filesystems locally. This exception serves as
 * a trace point for the actual transfer, so that the intended operation can be observed in a stack,
 * with all constituent exceptions available for observation.
 */
class BulkTransferException : IOException {
    // No empty BulkTransferException is ever thrown.
    private var allCacheNotFoundException = true

    constructor()

    constructor(e: IOException) {
        add(e)
    }

    /**
     * Adds an IOException to the suppressed list.
     * 
     * 
     * If the IOException is already a BulkTransferException, the contained IOExceptions are added
     * instead.
     * 
     * 
     * The Java standard addSuppressed is final and this method stands in its place to selectively
     * filter and record whether all suppressed exceptions are CacheNotFoundExceptions.
     */
    fun add(e: IOException) {
        if (e is BulkTransferException) {
            for (t in e.getSuppressed()) {
                if (t is IOException) {
                    add(t)
                } else {
                    throw java.lang.IllegalStateException("BulkTransferException contains non-IOException", t)
                }
            }
            return
        }
        allCacheNotFoundException = allCacheNotFoundException and e is CacheNotFoundException
        super.addSuppressed(e)
    }

    fun allCausedByCacheNotFoundException(): Boolean {
        return allCacheNotFoundException
    }

    /**
     * Returns a [LostArtifacts] instance that is non-empty if and only if all suppressed
     * exceptions are caused by cache misses.
     */
    fun getLostArtifacts(actionInputResolver: java.util.function.Function<PathFragment?, ActionInput?>): LostArtifacts? {
        if (!Companion.allCausedByCacheNotFoundException(this)) {
            return LostArtifacts.EMPTY
        }

        val byDigestBuilder: com.google.common.collect.ImmutableSetMultimap.Builder<String?, ActionInput?> =
            com.google.common.collect.ImmutableSetMultimap.builder<String?, ActionInput?>()
        for (suppressed in getSuppressed()) {
            val e: CacheNotFoundException = suppressed as CacheNotFoundException
            val missingDigest: Digest = e.getMissingDigest()
            val execPath: PathFragment? = e.getExecPath()
            if (execPath == null) {
                // This can happen if the lost artifact is not an input of the action, but a special output
                // such as stdout/stderr. This can't be solved by the rewinding that LostArtifacts would
                // trigger, but is rather a failure of the current action execution.
                if (e.getFilename() == null) {
                    throw java.lang.IllegalArgumentException(
                        "CacheNotFoundException that may represent a lost artifact should have been annotated"
                                + " with a filename",
                        e
                    )
                }
                return LostArtifacts.EMPTY
            }
            val actionInput: ActionInput? = actionInputResolver.apply(execPath)
            if (actionInput == null) {
                // This can happen if the lost artifact is not an input of the action, but an output that
                // e.g. failed to be retrieved from the remote cache after a cache hit. This also can't be
                // solved by the rewinding that LostArtifacts would trigger.
                return LostArtifacts.EMPTY
            }
            byDigestBuilder.put(DigestUtil.toString(missingDigest), actionInput)
        }
        val byDigest: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?> = byDigestBuilder.build()
        return LostArtifacts(byDigest)
    }

    val message: String?
        get() {
            // Only report unique messages to avoid flooding the user, e.g. in case a remote cache server is
            // unavailable
            // and causing several identical messages. Also sort the messages, for more deterministic
            // result. All of this allows
            // more efficient event deduplication when reporting the returned aggregated message.
            val uniqueSortedMessages: MutableList<String?> =
                java.util.Arrays.stream<Throwable?>(super.getSuppressed())
                    .map<String?>(java.util.function.Function { obj: Throwable? -> obj.getMessage() })
                    .filter(java.util.function.Predicate { obj: String? -> java.util.Objects.nonNull(obj) })
                    .sorted()
                    .distinct()
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

            return when (uniqueSortedMessages.size()) {
                0 -> "Unknown error during bulk transfer"
                1 -> com.google.common.collect.Iterables.getOnlyElement<String?>(uniqueSortedMessages)
                else -> "Multiple errors during bulk transfer:\n" + com.google.common.base.Joiner.on("\n")
                    .join(uniqueSortedMessages)
            }
        }

    companion object {
        fun allCausedByCacheNotFoundException(e: Throwable?): Boolean {
            return e is BulkTransferException
                    && e.allCausedByCacheNotFoundException()
        }
    }
}
