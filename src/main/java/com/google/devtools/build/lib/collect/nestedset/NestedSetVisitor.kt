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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSet.VisitedArraySet

/**
 * NestedSetVisitor facilitates a transitive visitation over a NestedSet. The callback may be called
 * from multiple threads, and must be thread-safe.
 * 
 * 
 * The visitation is iterative: The caller may invoke a NestedSet within the top-level NestedSet
 * in any order.
 * 
 * @param <E> the data type
</E> */
class NestedSetVisitor<E>(callback: Receiver<E?>?, visited: VisitedState<E?>?) {
    /**
     * For each element of the NestedSet the `Receiver` will receive one element during the
     * visitation.
     */
    interface Receiver<E> {
        fun accept(arg: E?)
    }

    private val callback: Receiver<E?>

    private val visited: VisitedState<E?>

    init {
        this.callback = com.google.common.base.Preconditions.checkNotNull<Receiver<E?>>(callback)
        this.visited = com.google.common.base.Preconditions.checkNotNull<VisitedState<E?>>(visited)
    }

    /**
     * Transitively visit a nested set.
     * 
     * @param nestedSet the nested set to visit transitively.
     */
    @Throws(java.lang.InterruptedException::class)
    fun visit(nestedSet: NestedSet<E?>) {
        // We can short-circuit empty nested set visitation here, avoiding load on the shared map
        // VisitedState#seenNodes.
        if (!nestedSet.isEmpty()) {
            visitRaw(nestedSet.getChildrenInterruptibly())
        }
    }

    /** Visit every entry in a collection.  */
    fun visit(collection: MutableCollection<E?>) {
        for (e in collection) {
            if (visited.needToVisitLeaf.test(e)) {
                callback.accept(e)
            }
        }
    }

    private fun visitRaw(node: Any?) {
        if (node is Array<Any>) {
            if (visited.needToVisitNonLeaf.test(node)) {
                for (child in node) {
                    visitRaw(child)
                }
            }
        } else {
            val leaf// It's not an Object[] so must be a leaf.
                    = node as E?
            if (visited.needToVisitLeaf.test(leaf)) {
                callback.accept(leaf)
            }
        }
    }

    /** Allows [NestedSetVisitor] to keep track of the seen nodes and transitive sets.  */
    class VisitedState<E> private constructor(
        needToVisitNonLeaf: java.util.function.Predicate<Array<Any?>?>?,
        needToVisitLeaf: java.util.function.Predicate<E?>?
    ) {
        private val needToVisitNonLeaf: java.util.function.Predicate<Array<Any?>?>
        private val needToVisitLeaf: java.util.function.Predicate<E?>

        init {
            this.needToVisitNonLeaf =
                com.google.common.base.Preconditions.checkNotNull<java.util.function.Predicate<Array<Any?>?>>(
                    needToVisitNonLeaf
                )
            this.needToVisitLeaf =
                com.google.common.base.Preconditions.checkNotNull<java.util.function.Predicate<E?>>(needToVisitLeaf)
        }

        companion object {
            /** Creates a new visited state with the given predicate of whether to visit leaves.  */
            fun <E> create(needToVisitLeaf: java.util.function.Predicate<E?>?): VisitedState<E?> {
                return VisitedState<E?>(java.util.function.Predicate { array: Array<Any?>? ->
                    VisitedArraySet().add(
                        array
                    )
                }, needToVisitLeaf)
            }

            /**
             * Creates a new thread-safe visited state with the given predicate of whether to visit leaves.
             */
            fun <E> createConcurrent(needToVisitLeaf: java.util.function.Predicate<E?>?): VisitedState<E?> {
                return VisitedState<E?>(
                    java.util.function.Predicate { obj: Array<Any?>? ->
                        com.google.devtools.build.lib.collect.ConcurrentIdentitySet( /* sizeHint= */1024).add(obj)
                    }, needToVisitLeaf
                )
            }
        }
    }
}
