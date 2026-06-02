// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect

/**
 * A set that performs identity-based deduplication.
 * 
 * 
 * This class is thread-safe except as noted.
 * 
 * 
 * It optimistically performs lookups without locking and locks only when mutations are needed,
 * possibly causing some operations to be retried.
 * 
 * 
 * This class uses closed hashing and thus does not need entry wrappers, making it
 * memory-efficient. The memory savings compared to [ ][com.google.common.collect.Sets.newConcurrentHashSet] is approximately 32 fewer bytes per entry.
 */
// TODO(b/17553173): Can this (perhaps with value equality) be used more widely to save memory?
class ConcurrentIdentitySet : Cloneable {
    @kotlin.concurrent.Volatile
    private var data: Array<Any?>
    private var size = 0

    private constructor(data: Array<Any?>, size: Int) {
        this.data = data
        this.size = size
    }

    /**
     * @param sizeHint how many elements to store without resizing
     */
    constructor(sizeHint: Int) {
        var size = 1
        while (size <= sizeHint) {
            size *= 2
        }
        this.data = arrayOfNulls<Any>(size * 2)
    }

    /**
     * Tries to add `obj` to the set of tracked identities.
     * 
     * @return true if `obj` was absent and added to the set
     */
    fun add(obj: Any?): Boolean {
        com.google.common.base.Preconditions.checkNotNull<Any?>(obj)
        val hashCode: Int = java.lang.System.identityHashCode(obj)
        while (true) {
            val snapshot = data
            var probe: Int = com.google.devtools.build.lib.collect.ConcurrentIdentitySet.Companion.hash( /* hashCode= */
                hashCode,  /* length= */
                snapshot.length
            )
            var probedValue = snapshot[probe]
            while (true) {
                if (probedValue != null) {
                    if (probedValue === obj) {
                        return false // Duplicate found.
                    }
                    if (++probe == snapshot.length) {
                        probe = 0
                    }
                    probedValue = snapshot[probe]
                    continue
                }
                // probe points to a likely empty slot
                synchronized(this) {
                    if (snapshot != data) {
                        break // Another thread updated the snapshot.
                    }
                    // Re-reads the probed value under lock. It's possible another thread updated it.
                    probedValue = snapshot[probe]
                    if (probedValue != null) {
                        continue
                    }
                    snapshot[probe] = obj
                    if (++size * 2 >= snapshot.length) {
                        resize()
                    }
                }
                return true
            }
        }
    }

    /** Not thread safe.  */
    fun clear() {
        java.util.Arrays.fill(data, null)
        size = 0
    }

    public override fun clone(): ConcurrentIdentitySet {
        return com.google.devtools.build.lib.collect.ConcurrentIdentitySet(data.clone(), size)
    }

    /** Requires synchronized (this).  */
    private fun resize() {
        val newData = arrayOfNulls<Any>(data.length * 2)
        for (obj in data) {
            if (obj == null) {
                continue
            }
            var probe: Int =
                com.google.devtools.build.lib.collect.ConcurrentIdentitySet.Companion.hash( /*hashCode=*/java.lang.System.identityHashCode(
                    obj
                ),  /*length=*/newData.length
                )
            while (newData[probe] != null) {
                if (++probe == newData.length) {
                    probe = 0
                }
            }
            // No need to check for equality because all values are unique.
            newData[probe] = obj
        }
        data = newData
    }

    companion object {
        /** Copied from [java.util.IdentityHashMap].  */
        private fun hash(hashCode: Int, length: Int): Int {
            return ((hashCode shl 1) - (hashCode shl 8)) and (length - 1)
        }
    }
}
