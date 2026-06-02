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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.BlazeInterners

/**
 * Container for a pending operation on the reverse deps set. We use subclasses to save 8 bytes of
 * memory instead of keeping a field in this class, and we store [Op.CHECK] or [Op.ADD]
 * operations as the bare [SkyKey] in order to save the wrapper object in that case.
 * 
 * 
 * When a list of [KeyToConsolidate] operations is processed, each operation is performed
 * in order. Operations on a done or freshly evaluating node entry are straightforward: they apply
 * to the entry's reverse deps. Operations on a re-evaluating node entry have a double meaning: they
 * will eventually be applied to the node entry's existing reverse deps, just as for a done node
 * entry, but they are also used to track the entries that declared/redeclared a reverse dep on this
 * entry during this evaluation (and will thus need to be signaled when this entry finishes
 * evaluating).
 */
abstract class KeyToConsolidate private constructor(key: SkyKey) {
    internal enum class Op {
        /**
         * If the entry is re-evaluating, assert that the reverse dep is already present in the set of
         * reverse deps and add this reverse dep to the set of reverse deps to signal when this entry is
         * done. If the entry is already done, do nothing.
         */
        CHECK,

        /**
         * Add the reverse dep to the set of reverse deps and assert it was not already present. If the
         * entry is re-evaluating, add this reverse dep to the set of reverse deps to signal when this
         * entry is done.
         */
        ADD,

        /**
         * Remove the reverse dep from the set of reverse deps and assert it was present. If the entry
         * is re-evaluating, also remove the reverse dep from the set of reverse deps to signal when
         * this entry is done.
         */
        REMOVE
    }

    private val key: SkyKey

    /** Do not call directly -- use the [.create] static method instead.  */
    init {
        this.key = key
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("key", key).toString()
    }

    override fun equals(obj: Any?): Boolean {
        if (obj == null) {
            return false
        }
        return this.getClass() == obj.getClass() && this.key == (obj as KeyToConsolidate).key
    }

    fun keyHashCode(): Int {
        return key.hashCode()
    }

    override fun hashCode(): Int {
        // Overridden in subclasses.
        throw java.lang.UnsupportedOperationException(key.toString())
    }

    private class KeyToAdd(key: SkyKey) : KeyToConsolidate(key) {
        override fun hashCode(): Int {
            return keyHashCode()
        }
    }

    private class KeyToCheck(key: SkyKey) : KeyToConsolidate(key) {
        override fun hashCode(): Int {
            return 31 + 43 * keyHashCode()
        }
    }

    private class KeyToRemove(key: SkyKey) : KeyToConsolidate(key) {
        override fun hashCode(): Int {
            return 42 + 37 * keyHashCode()
        }
    }

    companion object {
        private val consolidateInterner: com.google.common.collect.Interner<KeyToConsolidate> =
            BlazeInterners.newWeakInterner()

        /**
         * Gets which operation was delayed for the given object, created using [.create]. The same
         * `opToStoreBare` passed in to [.create] should be passed in here.
         */
        fun op(obj: Any, opToStoreBare: Op?): Op? {
            if (obj is SkyKey) {
                return opToStoreBare
            }
            if (obj is KeyToAdd) {
                return com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD
            }
            if (obj is KeyToCheck) {
                return com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK
            }
            if (obj is KeyToRemove) {
                return com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE
            }
            throw java.lang.IllegalStateException(
                "Unknown object type: " + obj + ", " + opToStoreBare + ", " + obj.getClass()
            )
        }

        /** Gets the key whose operation was delayed for the given object.  */
        fun key(obj: Any?): SkyKey? {
            if (obj is SkyKey) {
                return obj
            }
            com.google.common.base.Preconditions.checkState(obj is KeyToConsolidate, obj)
            return (obj as KeyToConsolidate).key
        }

        /**
         * Creates a new operation, encoding the operation `op` with reverse dep `key`. To
         * save memory, the caller should specify the most common operation expected as `opToStoreBare`. That operation will be encoded as the raw `key`, saving the memory of an
         * object wrapper. Whatever `opToStoreBare` is set to here, the same value must be passed in
         * to [.op] when decoding an operation emitted by this method.
         */
        fun create(key: SkyKey, op: Op, entry: IncrementalInMemoryNodeEntry): Any {
            com.google.common.base.Preconditions.checkNotNull<SkyKey?>(key)
            if (op == ReverseDepsUtility.getOpToStoreBare(entry)) {
                return key
            }
            when (op) {
                com.google.devtools.build.skyframe.KeyToConsolidate.Op.CHECK -> return consolidateInterner.intern(
                    KeyToCheck(key)
                )

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.REMOVE -> return consolidateInterner.intern(
                    KeyToRemove(key)
                )

                com.google.devtools.build.skyframe.KeyToConsolidate.Op.ADD -> return consolidateInterner.intern(
                    KeyToAdd(
                        key
                    )
                )

                else -> throw java.lang.IllegalStateException(op.toString() + ", " + key)
            }
        }
    }
}
