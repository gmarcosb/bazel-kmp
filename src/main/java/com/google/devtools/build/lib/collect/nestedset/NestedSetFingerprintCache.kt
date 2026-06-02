// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.actions.CommandLineExpansionException

/** Computes fingerprints for nested sets, reusing sub-computations from children.  */
open class NestedSetFingerprintCache {
    /** Memoize the subresults. We have to have one cache per type of command item map function.  */
    private var mapFnToDigestMap: MutableMap<CommandLineItem.MapFn<*>?, DigestMap> = createMap()

    private val seenMapFns: MutableSet<java.lang.Class<*>?> = HashSet<java.lang.Class<*>?>()
    private val seenParametrizedMapFns: com.google.common.collect.Multiset<java.lang.Class<*>?> =
        com.google.common.collect.HashMultiset.create<java.lang.Class<*>?>()

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun <T> addNestedSetToFingerprint(fingerprint: Fingerprint, nestedSet: NestedSet<T?>) {
        addNestedSetToFingerprintExceptionless<T?>(CommandLineItem.MapFn.DEFAULT, fingerprint, nestedSet)
    }

    fun <T> addNestedSetToFingerprintExceptionless(
        mapFn: CommandLineItem.ExceptionlessMapFn<in T?>?,
        fingerprint: Fingerprint,
        nestedSet: NestedSet<T?>
    ) {
        try {
            addNestedSetToFingerprint<T?>(mapFn as CommandLineItem.MapFn<in T?>?, fingerprint, nestedSet)
        } catch (e: CommandLineExpansionException) {
            // addNestedSetToFingerprint only throws these exceptions if mapFn does.
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    @Throws(
        CommandLineExpansionException::class,
        java.lang.InterruptedException::class
    )  // ordinal not used across different binary versions
    fun <T> addNestedSetToFingerprint(
        mapFn: CommandLineItem.MapFn<in T?>, fingerprint: Fingerprint, nestedSet: NestedSet<T?>
    ) {
        // Only top-level nested sets can be empty, so we can bail here
        if (nestedSet.isEmpty()) {
            fingerprint.addInt(EMPTY_SET_DIGEST)
            return
        }
        val digestMap: DigestMap = mapFnToDigestMap.computeIfAbsent(
            mapFn,
            java.util.function.Function { mapFn: CommandLineItem.MapFn<*>? -> this.newDigestMap(mapFn) })
        fingerprint.addInt(nestedSet.getOrder().ordinal())
        val children: Any? = nestedSet.getChildren()
        addToFingerprint<Any?>(
            mapFn,
            children,
            digestMap,  /* transitiveDigestDeduper= */
            null,
            fingerprint,
            DigestReference()
        )
    }

    fun clear() {
        mapFnToDigestMap = createMap()
        seenMapFns.clear()
        seenParametrizedMapFns.clear()
    }

    // safe cast of direct child to T after checking it's not a child array.
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun <T> addToFingerprint(
        mapFn: CommandLineItem.MapFn<in T?>,
        rawChildren: Any?,
        digestMap: DigestMap,  // Deduplicator for any Object[] children in `rawChildren`. The top level `rawChildren`
        // instance has no siblings so this is null in that case. It's non-null on recursive calls.
        transitiveDigestDeduper: DigestDeduper?,
        fingerprint: Fingerprint,  // A reusable buffer for digest references.
        digestBuffer: DigestReference
    ) {
        if (rawChildren !is Array<Any>) {
            // It was an immediate child. These should already be deduplicated by the NestedSetBuilder.
            addToFingerprint<T?>(mapFn, fingerprint, rawChildren as T?)
            return
        }

        if (digestMap.readDigest(rawChildren, digestBuffer)) {
            // Adds novel sets to the fingerprint and skips duplicates.
            if (transitiveDigestDeduper == null || transitiveDigestDeduper.add(digestBuffer)) {
                digestBuffer.addTo(fingerprint)
            }
            digestBuffer.clear()
            return
        }

        val childArrayFingerprinter: Fingerprint = Fingerprint()

        // `childArrayDeduper` is used to deduplicate transitive sets within the *same* direct child
        // array of a NestedSet. Note that Object[] children across different nodes of a NestedSet graph
        // cannot be deduplicated in this way because we are memoizing their fingerprints. For example,
        // let P be a parent Object[], U be an uncle Object[] and C be the child Object[]. Furthermore,
        // suppose that C is a duplicate of U. If we were to deduplicate C against U, the fingerprint of
        // P would change if P were reused in a different NestedSet without the presence of U, defeating
        // fingerprint memoization.
        val childArrayDeduper: DigestDeduper =
            DigestDeduper( /* maxSize= */
                rawChildren.length,  /* digestLength= */digestMap.getMaxDigestLength()
            )

        for (child in rawChildren) {
            addToFingerprint<Any?>(
                mapFn, child, digestMap, childArrayDeduper, childArrayFingerprinter, digestBuffer
            )
        }

        digestMap.insertAndReadDigest(rawChildren, childArrayFingerprinter, digestBuffer)
        if (transitiveDigestDeduper == null || transitiveDigestDeduper.add(digestBuffer)) {
            digestBuffer.addTo(fingerprint)
        }
        digestBuffer.clear()
    }

    // Non-static for testability.
    @com.google.common.annotations.VisibleForTesting
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    open fun <T> addToFingerprint(
        mapFn: CommandLineItem.MapFn<in T?>, fingerprint: Fingerprint, `object`: T?
    ) {
        mapFn.expandToCommandLine(`object`, { input: String? -> fingerprint.addString(input) })
    }

    private fun newDigestMap(mapFn: CommandLineItem.MapFn<*>): DigestMap {
        val mapFnClass: java.lang.Class<*> = mapFn.getClass()
        if (mapFn is CommandLineItem.ParametrizedMapFn) {
            val occurrences: Int = seenParametrizedMapFns.add(mapFnClass, 1) + 1
            require(!(occurrences > (mapFn as CommandLineItem.ParametrizedMapFn).maxInstancesAllowed())) {
                java.lang.String.format(
                    "Too many instances of CommandLineItem.ParametrizedMapFn '%s' detected. "
                            + "Please construct fewer instances.",
                    mapFnClass.getName()
                )
            }
        } else {
            require(seenMapFns.add(mapFnClass)) {
                java.lang.String.format(
                    ("Illegal mapFn implementation: '%s'. "
                            + "CommandLineItem.MapFn instances must be singletons."
                            + "Please see CommandLineItem.ParametrizedMapFn for an alternative."),
                    mapFnClass.getName()
                )
            }
        }
        // TODO(b/112460990): Use the value from DigestHashFunction.getDefault(), but check for
        // contention.
        return DigestMap(DigestHashFunction.SHA256, 1024)
    }

    companion object {
        private const val EMPTY_SET_DIGEST = 104395303

        fun <T> describedNestedSetFingerprint(
            mapFn: CommandLineItem.ExceptionlessMapFn<in T?>, nestedSet: NestedSet<T?>
        ): String {
            if (nestedSet.isEmpty()) {
                return "<empty>"
            }
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            sb.append("order: ")
                .append(nestedSet.getOrder())
                .append(
                    " (fingerprinting considers internal"
                            + " nested set structure, which is not reflected in values reported below)\n"
                )
            val list: com.google.common.collect.ImmutableList<T?> = nestedSet.toList()
            sb.append("size: ").append(list.size()).append('\n')
            for (item in list) {
                sb.append("  ")
                mapFn.expandToCommandLine(item, { s -> sb.append(s).append(", ") })
                sb.append('\n')
            }
            return sb.toString()
        }

        private fun createMap(): MutableMap<CommandLineItem.MapFn<*>?, DigestMap> {
            return ConcurrentHashMap<CommandLineItem.MapFn<*>?, DigestMap>()
        }
    }
}
