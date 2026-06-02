// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.compacthashset.CompactHashSet

/**
 * A utility class that allows us to store reverse dependencies in a memory-efficient way. At the
 * same time it allows us to group the removals and uniqueness checks so that it also performs well.
 * 
 * 
 * The operations [.addReverseDep] and [.removeReverseDep] here are optimized for a
 * done entry. Done entries rarely have rdeps added and removed, but do have [Op.CHECK]
 * operations performed frequently. As well, done node entries may never have their data forcibly
 * consolidated, since their reverse deps will only be retrieved as a whole if they are marked
 * dirty. Thus, we consolidate periodically.
 * 
 * 
 * [IncrementalInMemoryNodeEntry] manages pending reverse dep operations on a marked-dirty
 * or initially evaluating node itself, using similar logic tuned to those cases, and calls into
 * [.consolidateDataAndReturnNewElements] when transitioning to done.
 * 
 * 
 * The storage schema for reverse dependencies of done node entries is:
 * 
 * 
 *  * 0 rdeps: empty [ImmutableList]
 *  * 1 rdep: bare [SkyKey]
 *  * 2-4 rdeps: `SkyKey[]` (no nulls)
 *  * 5+ rdeps: an [ArrayList]
 * 
 * 
 * This strategy saves memory in the common case of few reverse deps while still supporting constant
 * time additions for nodes with many rdeps by dynamically switching to an [ArrayList].
 */
internal object ReverseDepsUtility {
    /**
     * Returns the [Op] to store bare instead of wrapping in [KeyToConsolidate].
     * 
     * 
     * We can store one type of operation bare in order to save memory. For nodes on their initial
     * build and nodes not keeping reverse deps, most operations are [Op.ADD].
     * 
     * 
     * Done nodes have very few delayed ops - [Op.CHECK] is never stored on a done node and
     * [Op.ADD] is only delayed if there are already pending delayed ops. Returning [ ][Op.CHECK] in this case just makes it easy to distinguish from nodes on their initial build.
     */
    fun getOpToStoreBare(entry: AbstractInMemoryNodeEntry<*>): com.google.devtools.build.skyframe.KeyToConsolidate.Op {
        val dirtyBuildingState: DirtyBuildingState? = entry.dirtyBuildingState
        if (dirtyBuildingState == null) {
            return com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK
        }
        return if (dirtyBuildingState.isIncremental()) com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK else com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD
    }

    private fun maybeDelayReverseDepOp(
        entry: IncrementalInMemoryNodeEntry,
        reverseDep: SkyKey?,
        op: com.google.devtools.build.skyframe.KeyToConsolidate.Op?
    ) {
        var consolidations: MutableList<Any?>? = entry.getReverseDepsDataToConsolidateForReverseDepsUtil()
        val currentReverseDepSize = sizeOf(entry.getReverseDepsRawForReverseDepsUtil())
        if (consolidations == null) {
            consolidations = java.util.ArrayList<Any?>(currentReverseDepSize)
            entry.setReverseDepsDataToConsolidateForReverseDepsUtil(consolidations)
        }
        consolidations!!.add(KeyToConsolidate.Companion.create(reverseDep, op, entry))
        // TODO(janakr): Should we consolidate more aggressively? This threshold can be customized.
        if (consolidations.size() >= currentReverseDepSize) {
            consolidateData(entry)
        }
    }

    private fun isSingleReverseDep(raw: Any?): Boolean {
        return raw is SkyKey
    }

    private fun multipleAsList(raw: Any): MutableList<SkyKey?> {
        return if (raw is Array<SkyKey>) java.util.Arrays.asList<SkyKey?>(*raw as Array<SkyKey?>) else raw as MutableList<SkyKey?>?
    }

    private fun sizeOf(raw: Any): Int {
        if (isSingleReverseDep(raw)) {
            return 1
        }
        if (raw is Array<SkyKey>) {
            return (raw as Array<SkyKey?>).size
        }
        return (raw as MutableList<*>).size()
    }

    fun addReverseDep(entry: IncrementalInMemoryNodeEntry, newReverseDep: SkyKey?) {
        val dataToConsolidate: MutableList<Any?>? = entry.getReverseDepsDataToConsolidateForReverseDepsUtil()
        if (dataToConsolidate != null) {
            maybeDelayReverseDepOp(entry, newReverseDep, com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD)
            return
        }
        val raw: Any = entry.getReverseDepsRawForReverseDepsUtil()
        val newSize = sizeOf(raw) + 1
        if (newSize == 1) {
            entry.setReverseDepsForReverseDepsUtil(newReverseDep)
        } else if (newSize == 2) {
            entry.setReverseDepsForReverseDepsUtil(arrayOf<SkyKey?>(raw as SkyKey?, newReverseDep))
        } else if (newSize <= 4) {
            val newArray: Array<SkyKey?> = java.util.Arrays.copyOf<SkyKey?>(raw as Array<SkyKey?>?, newSize)
            newArray[newSize - 1] = newReverseDep
            entry.setReverseDepsForReverseDepsUtil(newArray)
        } else if (newSize == 5) {
            val newList: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>(8)
            Collections.addAll<SkyKey?>(newList, *raw as Array<SkyKey?>?)
            newList.add(newReverseDep)
            entry.setReverseDepsForReverseDepsUtil(newList)
        } else {
            (raw as MutableList<SkyKey?>).add(newReverseDep)
        }
    }

    /** See [.addReverseDep] method.  */
    fun removeReverseDep(entry: IncrementalInMemoryNodeEntry, reverseDep: SkyKey?) {
        maybeDelayReverseDepOp(entry, reverseDep, com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE)
    }

    fun removeReverseDepsMatching(
        entry: IncrementalInMemoryNodeEntry, deletedKeys: MutableSet<SkyKey?>
    ) {
        consolidateData(entry)
        val currentReverseDeps: com.google.common.collect.ImmutableSet<SkyKey?> =
            com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(
                consolidateAndGetReverseDeps(
                    entry,  /* checkConsistency= */
                    true
                )
            )
        writeReverseDepsSet(entry, com.google.common.collect.Sets.difference<SkyKey?>(currentReverseDeps, deletedKeys))
    }

    fun consolidateAndGetReverseDeps(
        entry: IncrementalInMemoryNodeEntry, checkConsistency: Boolean
    ): com.google.common.collect.ImmutableCollection<SkyKey?> {
        consolidateData(entry)

        // TODO(bazel-team): Unfortunately, we need to make a copy here right now to be on the safe side
        // wrt. thread-safety. The parents of a node get modified when any of the parents is deleted,
        // and we can't handle that right now.
        val raw: Any = entry.getReverseDepsRawForReverseDepsUtil()
        if (isSingleReverseDep(raw)) {
            return com.google.common.collect.ImmutableSet.of<SkyKey?>(raw as SkyKey)
        } else {
            val reverseDeps: MutableList<SkyKey?> = multipleAsList(raw)
            if (!checkConsistency) {
                return com.google.common.collect.ImmutableList.copyOf<SkyKey?>(reverseDeps)
            }
            val set: com.google.common.collect.ImmutableSet<SkyKey?> =
                com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(reverseDeps)
            if (set.size() != reverseDeps.size()) {
                val seen: MutableSet<SkyKey?> = HashSet<SkyKey?>()
                val duplicates: MutableSet<SkyKey?> = HashSet<SkyKey?>()
                for (key in reverseDeps) {
                    if (seen.add(key)) {
                        duplicates.add(key)
                    }
                }
                throw java.lang.IllegalStateException(
                    java.lang.String.format("In node %s: duplicate reverse deps present: %s", entry, duplicates)
                )
            }
            return set
        }
    }

    fun returnNewElements(entry: IncrementalInMemoryNodeEntry): MutableSet<SkyKey?>? {
        return consolidateDataAndReturnNewElements(entry,  /* mutateObject= */false)
    }

    private fun consolidateDataAndReturnNewElements(
        entry: IncrementalInMemoryNodeEntry, mutateObject: Boolean
    ): MutableSet<SkyKey?>? {
        val dataToConsolidate: MutableList<Any?>? = entry.getReverseDepsDataToConsolidateForReverseDepsUtil()
        if (dataToConsolidate == null) {
            return com.google.common.collect.ImmutableSet.of<SkyKey?>()
        }

        // On a node's initial build (or if not keeping rdeps), we don't need to build up two different
        // sets for "all reverse deps" and "new reverse deps" - they are all new.
        val opToStoreBare: com.google.devtools.build.skyframe.KeyToConsolidate.Op = getOpToStoreBare(entry)
        val allRdepsAreNew = opToStoreBare == com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD
        val allReverseDeps: MutableSet<SkyKey?>?
        val newReverseDeps: MutableSet<SkyKey?>
        val raw: Any = entry.getReverseDepsRawForReverseDepsUtil()
        if (isSingleReverseDep(raw)) {
            com.google.common.base.Preconditions.checkState(!allRdepsAreNew, entry)
            allReverseDeps = CompactHashSet.create(raw as SkyKey)
            newReverseDeps = CompactHashSet.create()
        } else {
            val reverseDepsAsList: MutableList<SkyKey?> = multipleAsList(raw)
            if (allRdepsAreNew) {
                com.google.common.base.Preconditions.checkState(reverseDepsAsList.isEmpty(), entry)
                allReverseDeps = null
                newReverseDeps = CompactHashSet.createWithExpectedSize(dataToConsolidate.size())
            } else {
                allReverseDeps = getReverseDepsSet(entry, reverseDepsAsList)
                newReverseDeps = CompactHashSet.create()
            }
        }

        for (keyToConsolidate in dataToConsolidate) {
            val key: SkyKey = KeyToConsolidate.Companion.key(keyToConsolidate)
            when (KeyToConsolidate.Companion.op(keyToConsolidate, opToStoreBare)) {
                com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK -> {
                    com.google.common.base.Preconditions.checkState(!allRdepsAreNew, entry)
                    com.google.common.base.Preconditions.checkState(
                        allReverseDeps!!.contains(key),
                        "Reverse dep not present: %s %s %s %s",
                        keyToConsolidate,
                        allReverseDeps,
                        dataToConsolidate,
                        entry
                    )
                    com.google.common.base.Preconditions.checkState(
                        newReverseDeps.add(key),
                        "Duplicate new reverse dep: %s %s %s %s",
                        keyToConsolidate,
                        allReverseDeps,
                        dataToConsolidate,
                        entry
                    )
                }

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE -> {
                    if (!allRdepsAreNew) {
                        com.google.common.base.Preconditions.checkState(
                            allReverseDeps!!.remove(key),
                            "Reverse dep to be removed not present: %s %s %s %s",
                            keyToConsolidate,
                            allReverseDeps,
                            dataToConsolidate,
                            entry
                        )
                    }
                    com.google.common.base.Preconditions.checkState(
                        newReverseDeps.remove(key) || !allRdepsAreNew,
                        "Reverse dep to be removed not present: %s %s %s %s",
                        keyToConsolidate,
                        newReverseDeps,
                        dataToConsolidate,
                        entry
                    )
                }

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD -> {
                    if (!allRdepsAreNew) {
                        com.google.common.base.Preconditions.checkState(
                            allReverseDeps!!.add(key),
                            "Duplicate reverse deps: %s %s %s %s",
                            keyToConsolidate,
                            raw,
                            dataToConsolidate,
                            entry
                        )
                    }
                    com.google.common.base.Preconditions.checkState(
                        newReverseDeps.add(key),
                        "Duplicate new reverse deps: %s %s %s %s",
                        keyToConsolidate,
                        raw,
                        dataToConsolidate,
                        entry
                    )
                }
            }
        }
        if (mutateObject) {
            entry.setReverseDepsDataToConsolidateForReverseDepsUtil(null)
            ReverseDepsUtility.writeReverseDepsSet(entry, if (allRdepsAreNew) newReverseDeps else allReverseDeps)
        }
        return newReverseDeps
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun consolidateDataAndReturnNewElements(entry: IncrementalInMemoryNodeEntry): MutableSet<SkyKey?>? {
        return consolidateDataAndReturnNewElements(entry,  /* mutateObject= */true)
    }

    fun consolidateData(entry: IncrementalInMemoryNodeEntry) {
        val dataToConsolidate: MutableList<Any?>? = entry.getReverseDepsDataToConsolidateForReverseDepsUtil()
        if (dataToConsolidate == null) {
            return
        }
        entry.setReverseDepsDataToConsolidateForReverseDepsUtil(null)
        val raw: Any = entry.getReverseDepsRawForReverseDepsUtil()
        if (isSingleReverseDep(raw)) {
            com.google.common.base.Preconditions.checkState(
                dataToConsolidate.size() == 1,
                "dataToConsolidate not size 1 even though only one rdep: %s %s %s",
                dataToConsolidate,
                raw,
                entry
            )
            val keyToConsolidate: Any? = com.google.common.collect.Iterables.getOnlyElement<Any?>(dataToConsolidate)
            val key: SkyKey = KeyToConsolidate.Companion.key(keyToConsolidate)
            com.google.common.base.Preconditions.checkState(
                getOpToStoreBare(entry) == com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK,
                entry
            )
            when (KeyToConsolidate.Companion.op(
                keyToConsolidate,
                com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK
            )) {
                com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE -> {
                    entry.setReverseDepsForReverseDepsUtil(com.google.common.collect.ImmutableList.of<Any?>())
                    com.google.common.base.Preconditions.checkState(
                        key == raw,
                        "%s %s %s",
                        keyToConsolidate,
                        raw,
                        entry
                    )
                }

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK -> com.google.common.base.Preconditions.checkState(
                    key == raw,
                    "%s %s %s",
                    keyToConsolidate,
                    raw,
                    entry
                )

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD -> throw java.lang.IllegalStateException(
                    ("Shouldn't delay add if only one element: "
                            + keyToConsolidate
                            + ", "
                            + raw
                            + ", "
                            + entry)
                )
            }
            return
        }
        val reverseDepsAsList: MutableList<SkyKey?> = multipleAsList(raw)
        val reverseDepsAsSet: MutableSet<SkyKey?> = getReverseDepsSet(entry, reverseDepsAsList)

        com.google.common.base.Preconditions.checkState(
            getOpToStoreBare(entry) == com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK,
            entry
        )
        for (keyToConsolidate in dataToConsolidate) {
            val key: SkyKey = KeyToConsolidate.Companion.key(keyToConsolidate)
            when (KeyToConsolidate.Companion.op(
                keyToConsolidate,
                com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK
            )) {
                com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK -> com.google.common.base.Preconditions.checkState(
                    reverseDepsAsSet.contains(key),
                    "%s %s %s %s",
                    keyToConsolidate,
                    reverseDepsAsSet,
                    dataToConsolidate,
                    entry
                )

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE -> com.google.common.base.Preconditions.checkState(
                    reverseDepsAsSet.remove(key),
                    "%s %s %s %s",
                    keyToConsolidate,
                    raw,
                    dataToConsolidate,
                    entry
                )

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD -> com.google.common.base.Preconditions.checkState(
                    reverseDepsAsSet.add(key),
                    "%s %s %s %s",
                    keyToConsolidate,
                    raw,
                    dataToConsolidate,
                    entry
                )
            }
        }
        writeReverseDepsSet(entry, reverseDepsAsSet)
    }

    private fun writeReverseDepsSet(
        entry: IncrementalInMemoryNodeEntry, reverseDepsAsSet: MutableSet<SkyKey?>
    ) {
        if (!entry.keepsEdges() || reverseDepsAsSet.isEmpty()) {
            entry.setReverseDepsForReverseDepsUtil(com.google.common.collect.ImmutableList.of<Any?>())
        } else if (reverseDepsAsSet.size() == 1) {
            entry.setReverseDepsForReverseDepsUtil(
                com.google.common.collect.Iterables.getOnlyElement<SkyKey?>(
                    reverseDepsAsSet
                )
            )
        } else if (reverseDepsAsSet.size() <= 4) {
            entry.setReverseDepsForReverseDepsUtil(reverseDepsAsSet.toArray<SkyKey?>(java.util.function.IntFunction { _Dummy_.__Array__() }))
        } else {
            entry.setReverseDepsForReverseDepsUtil(java.util.ArrayList<SkyKey?>(reverseDepsAsSet))
        }
    }

    private fun getReverseDepsSet(
        entry: IncrementalInMemoryNodeEntry?, reverseDepsAsList: MutableList<SkyKey?>
    ): MutableSet<SkyKey?> {
        val reverseDepsAsSet: MutableSet<SkyKey?> = CompactHashSet.create(reverseDepsAsList)
        checkForDuplicates(reverseDepsAsSet, reverseDepsAsList, entry)
        return reverseDepsAsSet
    }

    fun checkForDuplicates(
        reverseDepsAsSet: MutableSet<SkyKey?>, reverseDepsAsList: MutableList<SkyKey?>, entry: InMemoryNodeEntry?
    ) {
        if (reverseDepsAsSet.size() == reverseDepsAsList.size()) {
            return
        }
        // We're about to crash. Try to print an informative error message.
        val seen: MutableSet<SkyKey?> = HashSet<SkyKey?>()
        val duplicates: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()
        for (key in reverseDepsAsList) {
            if (!seen.add(key)) {
                duplicates.add(key)
            }
        }
        throw java.lang.IllegalStateException(
            ((reverseDepsAsList.size() - reverseDepsAsSet.size())
                .toString() + " duplicate reverse deps: "
                    + duplicates
                    + " for "
                    + entry)
        )
    }

    fun toString(entry: IncrementalInMemoryNodeEntry): String {
        return com.google.common.base.MoreObjects.toStringHelper("ReverseDeps")
            .add("reverseDeps", entry.getReverseDepsRawForReverseDepsUtil())
            .add("singleReverseDep", isSingleReverseDep(entry))
            .add("dataToConsolidate", entry.getReverseDepsDataToConsolidateForReverseDepsUtil())
            .toString()
    }
}
