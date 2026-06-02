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
package com.google.devtools.build.lib.query2

/** Utility class for Skyframe-based query implementations.  */
internal object SkyQueryUtils {
    @Throws(java.lang.InterruptedException::class)
    fun <T> getTransitiveClosure(
        targets: ThreadSafeMutableSet<T?>, getFwdDeps: GetFwdDeps<T?>, visited: ThreadSafeMutableSet<T?>
    ): ThreadSafeMutableSet<T?> {
        var current: ThreadSafeMutableSet<T?> = targets
        while (!current.isEmpty()) {
            val toVisit: Iterable<T?> =
                current.stream().filter { obj: T? -> !visited.contains(obj) }.collect(Collectors.toList())
            current = getFwdDeps.getFwdDeps(toVisit)
            com.google.common.collect.Iterables.addAll<T?>(visited, toVisit)
        }
        return visited
    }

    /**
     * Gets a path from `from` to `to`, walking the graph revealed by `getFwdDeps`.
     * 
     * 
     * In case the type [T] does not implement equality, `label` will be used to map
     * elements of type [T] to elements of type [L] which does implement equality. `label` should be an injective function. For instance, if [T] is of type [Target]
     * then [L] could be of type [Label] and `label` could be [ ].
     * 
     * 
     * Implemented with a breadth-first search.
     */
    @Throws(java.lang.InterruptedException::class)
    fun <T, L> getNodesOnPath(
        from: T?, to: T?, getFwdDeps: GetFwdDeps<T?>, label: java.util.function.Function<T?, L?>
    ): com.google.common.collect.ImmutableList<T?>? {
        // Tree of nodes visited so far.
        val nodeToParent: MutableMap<L?, L?> = HashMap<L?, L?>()
        val labelToTarget: MutableMap<L?, T?> = HashMap<L?, T?>()
        // Contains all nodes left to visit in a (LIFO) stack.
        val toVisit: Deque<T?> = ArrayDeque<T?>()
        toVisit.add(from)
        nodeToParent.put(label.apply(from), null)
        labelToTarget.put(label.apply(from), from)
        while (!toVisit.isEmpty()) {
            val current: T? = toVisit.removeFirst()
            if (label.apply(to) == label.apply(current)) {
                val labelPath: MutableList<L?> = Digraph.getPathToTreeNode(nodeToParent, label.apply(to))
                val targetPathBuilder: com.google.common.collect.ImmutableList.Builder<T?> =
                    com.google.common.collect.ImmutableList.builder<T?>()
                for (item in labelPath) {
                    targetPathBuilder.add(
                        com.google.common.base.Preconditions.checkNotNull<T?>(
                            labelToTarget.get(item),
                            item
                        )
                    )
                }
                return targetPathBuilder.build()
            }
            for (dep in getFwdDeps.getFwdDeps(com.google.common.collect.ImmutableList.of<T?>(current))) {
                val depLabel: L? = label.apply(dep)
                if (!nodeToParent.containsKey(depLabel)) {
                    nodeToParent.put(depLabel, label.apply(current))
                    labelToTarget.put(depLabel, dep)
                    toVisit.addFirst(dep)
                }
            }
        }
        // Note that the only current caller of this method checks first to see if there is a path
        // before calling this method. It is not clear what the return value should be here.
        return null
    }

    internal interface GetFwdDeps<T> {
        @Throws(java.lang.InterruptedException::class)
        fun getFwdDeps(t: Iterable<T?>?): ThreadSafeMutableSet<T?>
    }
}
