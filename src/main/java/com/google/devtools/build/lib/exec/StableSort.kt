// Copyright 2019 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.exec

/**
 * A Utility to sort the execution log in a way that is reproducible across nondeterministic Bazel
 * runs.
 * 
 * 
 * This is needed to allow textual diff comparisons of resultant logs.
 */
object StableSort {
    /**
     * Reads [SpawnExec] protos from an [MessageInputStream], sorts them, and writes them
     * to a [MessageOutputStream].
     * 
     * 
     * The sort order has the following properties:
     * 
     * 
     *  1. If an output of spawn A is an input to spawn B, A sorts before B.
     *  1. When not constrained by the above, spawns sort in lexicographic order of their primary
     * output path.
     * 
     * 
     * 
     * Assumes that there are no cyclic dependencies.
     */
    @Throws(IOException::class)
    fun stableSort(
        `in`: MessageInputStream<SpawnExec?>, out: MessageOutputStream<SpawnExec?>
    ) {
        com.google.devtools.build.lib.profiler.Profiler.instance().profile("stableSort").use { c ->
            val inputs: java.util.ArrayList<SpawnExec> = java.util.ArrayList<SpawnExec>()
            com.google.devtools.build.lib.profiler.Profiler.instance().profile("stableSort/read").use { c2 ->
                var ex: SpawnExec?
                while ((`in`.read().also { ex = it }) != null) {
                    inputs.add(ex)
                }
            }
            // A map from each output to every spawn that produces it.
            // The same output may be produced by multiple spawns in the case of multiple test attempts.
            val outputProducer: com.google.common.collect.Multimap<String?, SpawnExec?> =
                com.google.common.collect.MultimapBuilder.hashKeys(inputs.size()).arrayListValues(1)
                    .build<String?, SpawnExec?>()

            for (ex in inputs) {
                for (output in ex.getActualOutputsList()) {
                    val name: String? = output.getPath()
                    outputProducer.put(name, ex)
                }
            }

            // A blocks B if A produces an output consumed by B.
            // Use reference equality to avoid expensive comparisons.
            val blockedBy: IdentitySetMultimap<SpawnExec?, SpawnExec?> = IdentitySetMultimap<SpawnExec?, SpawnExec?>()
            val blocking: IdentitySetMultimap<SpawnExec?, SpawnExec?> = IdentitySetMultimap<SpawnExec?, SpawnExec?>()

            // The queue contains all spawns whose blockers have already been emitted.
            val queue: java.util.PriorityQueue<SpawnExec?> =
                java.util.PriorityQueue<SpawnExec?>(
                    java.util.Comparator.comparing<SpawnExec?, String?>(
                        java.util.function.Function { o: SpawnExec? ->
                            // Sort by comparing the path of the first output. We don't want the sorting to
                            // rely on file hashes because we want the same action graph to be sorted in the
                            // same way regardless of file contents.
                            if (o.getListedOutputsCount() > 0) {
                                return@comparing "1_" + o.getListedOutputs(0)
                            }

                            // Get a proto with only stable information from this proto
                            val stripped: SpawnExec.Builder = SpawnExec.newBuilder()
                            stripped.addAllCommandArgs(o.getCommandArgsList())
                            stripped.addAllEnvironmentVariables(o.getEnvironmentVariablesList())
                            stripped.setPlatform(o.getPlatform())
                            stripped.addAllInputs(o.getInputsList())
                            stripped.setMnemonic(o.getMnemonic())
                            "2_" + stripped.build()
                        })
                )

            for (ex in inputs) {
                var blocked = false
                for (s in ex.getInputsList()) {
                    for (blocker in outputProducer.get(s.getPath())) {
                        blockedBy.put(ex, blocker)
                        blocking.put(blocker, ex)
                        blocked = true
                    }
                }
                if (!blocked) {
                    queue.add(ex)
                }
            }
            while (!queue.isEmpty()) {
                val curr: SpawnExec? = queue.remove()
                out.write(curr)

                for (blocked in blocking.get(curr)!!) {
                    blockedBy.remove(blocked, curr)
                    if (!blockedBy.containsKey(blocked)) {
                        queue.add(blocked)
                    }
                }
            }
        }
    }

    // A SetMultimap that uses reference equality for keys and values.
    // Implements only the subset of the SetMultimap API needed by stableSort().
    private class IdentitySetMultimap<K, V> {
        val map: IdentityHashMap<K?, MutableSet<V?>?> = IdentityHashMap<K?, MutableSet<V?>?>()

        fun containsKey(key: K?): Boolean {
            return map.containsKey(key)
        }

        fun get(key: K?): MutableSet<V?>? {
            return map.getOrDefault(key, com.google.common.collect.ImmutableSet.of<V?>())
        }

        fun put(key: K?, value: V?) {
            map.computeIfAbsent(
                key,
                java.util.function.Function { k: K? -> com.google.common.collect.Sets.newIdentityHashSet<V?>() })
                .add(value)
        }

        fun remove(key: K?, value: V?) {
            map.compute(
                key,
                java.util.function.BiFunction { unusedKey: K?, valueSet: MutableSet<V?>? ->
                    if (valueSet == null) {
                        return@compute null
                    }
                    valueSet.remove(value)
                    if (valueSet.isEmpty()) null else valueSet
                })
        }
    }
}
