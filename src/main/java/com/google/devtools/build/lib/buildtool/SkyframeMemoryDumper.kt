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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** An utility to dump the memory use of the objects in Skyframe in various ways.  */
class SkyframeMemoryDumper(// Fields affecting how the results are displayed
    private val displayMode: DisplayMode,
    private val needle: String?,
    ruleClassProvider: ConfiguredRuleClassProvider?,
    graph: InMemoryGraph,
    reportTransient: Boolean,
    reportConfiguration: Boolean,
    reportPrecomputed: Boolean,
    reportWorkspaceStatus: Boolean
) {
    /** How to display Skyframe memory use.  */
    enum class DisplayMode {
        /** Just a summary line  */
        SUMMARY,

        /** Object count by class  */
        COUNT,

        /** Bytes by class  */
        BYTES,
    }

    /** An exception that signals that dumping Skyframe memory did not work out.  */
    class DumpFailedException(message: String?, cause: Throwable?) : java.lang.Exception(message, cause)

    // Fields affecting how the data is collected
    private val reportTransient: Boolean
    private val ruleClassProvider: ConfiguredRuleClassProvider?
    private val fieldCache: FieldCache
    private val measurers: com.google.common.collect.ImmutableList<Measurer?>

    // Data that is being dumped
    private val graph: InMemoryGraph

    init {
        val buildObjectTraverser: BuildObjectTraverser =
            BuildObjectTraverser(reportConfiguration, reportPrecomputed, reportWorkspaceStatus)
        val collectionObjectTraverser: CollectionObjectTraverser = CollectionObjectTraverser()
        this.graph = graph
        this.ruleClassProvider = ruleClassProvider
        this.fieldCache =
            FieldCache(
                com.google.common.collect.ImmutableList.of<DomainSpecificTraverser?>(
                    buildObjectTraverser,
                    collectionObjectTraverser
                )
            )
        this.measurers = com.google.common.collect.ImmutableList.of<Measurer?>(collectionObjectTraverser)
        this.reportTransient = reportTransient
    }

    private fun addBuiltins(set: com.google.devtools.build.lib.collect.ConcurrentIdentitySet?) {
        val traverser: ObjectGraphTraverser =
            ObjectGraphTraverser(
                this.fieldCache,  /* countInternedObjects= */
                false,  /* reportTransientFields= */
                true,
                set,  /* collectContext= */
                false,
                ObjectGraphTraverser.NOOP_OBJECT_RECEIVER,  /* instanceId= */
                null
            )
        traverser.traverse(ruleClassProvider)
    }

    @Throws(java.lang.InterruptedException::class)
    fun dumpShallow(nodeEntry: NodeEntry): com.google.devtools.build.lib.util.MemoryAccountant.Stats? {
        val seenObjects: com.google.devtools.build.lib.collect.ConcurrentIdentitySet =
            com.google.devtools.build.lib.collect.ConcurrentIdentitySet(1)
        addBuiltins(seenObjects)

        // Mark all objects accessible from direct dependencies. This will mutate seen, but that's OK.
        for (directDepKey in nodeEntry.getDirectDeps()) {
            val directDepEntry: NodeEntry? = graph.get(null, QueryableGraph.Reason.OTHER, directDepKey)
            val depTraverser: ObjectGraphTraverser =
                ObjectGraphTraverser(
                    fieldCache,
                    false,
                    reportTransient,
                    seenObjects,
                    false,
                    ObjectGraphTraverser.NOOP_OBJECT_RECEIVER,
                    null
                )
            depTraverser.traverse(directDepEntry.getValue())
        }

        // Now traverse the objects reachable from the given SkyValue. Objects reachable from direct
        // dependencies are in "seen" and thus will not be counted.
        return dumpReachable(nodeEntry, seenObjects)
    }

    @Throws(java.lang.InterruptedException::class)
    fun dumpReachable(
        nodeEntry: NodeEntry,
        seenObjects: com.google.devtools.build.lib.collect.ConcurrentIdentitySet?
    ): com.google.devtools.build.lib.util.MemoryAccountant.Stats? {
        val memoryAccountant: MemoryAccountant =
            MemoryAccountant(
                measurers,
                displayMode != com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.SUMMARY
            )
        val traverser: ObjectGraphTraverser =
            ObjectGraphTraverser(
                fieldCache, true, reportTransient, seenObjects, true, memoryAccountant, null, needle
            )
        traverser.traverse(nodeEntry.getValue())
        return memoryAccountant.getStats()
    }

    @Throws(java.lang.InterruptedException::class)
    fun dumpReachable(nodeEntry: NodeEntry): com.google.devtools.build.lib.util.MemoryAccountant.Stats? {
        val seenObjects: com.google.devtools.build.lib.collect.ConcurrentIdentitySet =
            com.google.devtools.build.lib.collect.ConcurrentIdentitySet(1)
        addBuiltins(seenObjects)
        return dumpReachable(nodeEntry, seenObjects)
    }

    private fun processTransitive(
        processor: java.util.function.BiConsumer<SkyKey?, SkyValue?>,
        skyKey: SkyKey?,
        executor: java.util.concurrent.Executor,
        futureMap: MutableMap<SkyKey?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
        // This is awkward, but preferable to plumbing this through scheduleDeps and processDeps

        val value: Array<SkyValue?> = arrayOfNulls<SkyValue>(1)

        // First get the SkyValue and the direct deps from the Skyframe graph. This happens in a future
        // so that processTransitive() (which is called from computeIfAbsent()) doesn't throw a
        // checked exception.
        val fetchNodeData: com.google.common.util.concurrent.ListenableFuture<Iterable<SkyKey?>?> =
            com.google.common.util.concurrent.Futures.submit<Iterable<SkyKey?>?>(
                java.util.concurrent.Callable {
                    val entry: NodeEntry? = graph.get(null, QueryableGraph.Reason.OTHER, skyKey)
                    value[0] = entry.getValue()
                    entry.getDirectDeps()
                },
                executor
            )

        // This returns a list of futures representing processing the direct deps of this node
        val scheduleDeps: com.google.common.util.concurrent.ListenableFuture<com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>?> =
            com.google.common.util.concurrent.Futures.transform<Iterable<SkyKey?>?, com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>?>(
                fetchNodeData,
                com.google.common.base.Function { directDeps: Iterable<SkyKey?>? ->
                    val depFutures: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                        java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
                    for (dep in directDeps!!) {
                        // If the processing of this dependency has not been scheduled, do so
                        depFutures.add(
                            futureMap.computeIfAbsent(
                                dep,
                                java.util.function.Function { k: SkyKey? ->
                                    processTransitive(
                                        processor,
                                        dep,
                                        executor,
                                        futureMap
                                    )
                                })
                        )
                    }
                    com.google.common.collect.ImmutableList.copyOf<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(
                        depFutures
                    )
                },
                executor
            )

        // This is a future that gets completed when the direct deps have all been processed...
        val processDeps: com.google.common.util.concurrent.ListenableFuture<MutableList<java.lang.Void?>?> =
            com.google.common.util.concurrent.Futures.transformAsync<com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>?, MutableList<java.lang.Void?>?>(
                scheduleDeps,
                com.google.common.util.concurrent.AsyncFunction { futures: com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>? ->
                    com.google.common.util.concurrent.Futures.allAsList(futures)
                },
                executor
            )

        // ...and when that's the case, we can proceed with processing this node in turn.
        return com.google.common.util.concurrent.Futures.transform<MutableList<java.lang.Void?>?, java.lang.Void?>(
            processDeps,
            com.google.common.base.Function { unused: MutableList<java.lang.Void?>? ->
                processor.accept(skyKey, value[0])
                null
            },
            executor
        )
    }

    @Throws(java.lang.InterruptedException::class)
    fun dumpTransitive(skyKey: SkyKey?): com.google.devtools.build.lib.util.MemoryAccountant.Stats? {
        val seenObjects: com.google.devtools.build.lib.collect.ConcurrentIdentitySet =
            com.google.devtools.build.lib.collect.ConcurrentIdentitySet(1)
        addBuiltins(seenObjects)

        val memoryAccountant: MemoryAccountant =
            MemoryAccountant(
                measurers,
                displayMode != com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.SUMMARY
            )
        val processor: java.util.function.BiConsumer<SkyKey?, SkyValue?> =
            java.util.function.BiConsumer { unused: SkyKey?, skyValue: SkyValue? ->
                val traverser: ObjectGraphTraverser =
                    ObjectGraphTraverser(
                        fieldCache,
                        false,
                        reportTransient,
                        seenObjects,
                        true,
                        memoryAccountant,
                        null,
                        needle
                    )
                traverser.traverse(skyValue)
            }

        try {
            createExecutor().use { executor ->
                val work: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                    processTransitive(
                        processor,
                        skyKey,
                        executor,
                        ConcurrentHashMap<SkyKey?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
                    )
                work.get()
            }
        } catch (e: ExecutionException) {
            throw java.lang.IllegalStateException(e)
        }

        return memoryAccountant.getStats()
    }

    @Throws(java.lang.InterruptedException::class, DumpFailedException::class)
    fun dumpFull(out: PrintStream) {
        // Profiling shows that the average object count for a Skyframe node is around 30-40. Let's
        // go with 48 to avoid a potentially costly resize.
        val seenObjects: com.google.devtools.build.lib.collect.ConcurrentIdentitySet =
            com.google.devtools.build.lib.collect.ConcurrentIdentitySet(graph.getAllNodeEntries().size() * 48)
        addBuiltins(seenObjects)

        val roots: com.google.common.collect.ImmutableList<SkyKey?> =
            graph.getAllNodeEntries().parallelStream()
                .filter(java.util.function.Predicate { e: InMemoryNodeEntry? ->
                    e.isDone() && com.google.common.collect.Iterables.isEmpty(
                        e.getReverseDepsForDoneEntry()
                    )
                })
                .map<SkyKey?>(java.util.function.Function { obj: InMemoryNodeEntry? -> obj.getKey() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<SkyKey?>())

        val nodeStats: ConcurrentHashMap<SkyKey?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?> =
            ConcurrentHashMap<SkyKey?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>()

        val processor: java.util.function.BiConsumer<SkyKey?, SkyValue?> =
            java.util.function.BiConsumer { skyKey: SkyKey?, skyValue: SkyValue? ->
                val memoryAccountant: MemoryAccountant =
                    MemoryAccountant(
                        measurers,
                        displayMode != com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.SUMMARY
                    )
                val traverser: ObjectGraphTraverser =
                    ObjectGraphTraverser(
                        fieldCache,
                        true,
                        reportTransient,
                        seenObjects,
                        true,
                        memoryAccountant,
                        skyKey,
                        needle
                    )
                traverser.traverse(skyValue)
                val stats: com.google.devtools.build.lib.util.MemoryAccountant.Stats? = memoryAccountant.getStats()
                nodeStats.put(skyKey, stats)
            }

        try {
            createExecutor().use { executor ->
                val futureMap: ConcurrentHashMap<SkyKey?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                    ConcurrentHashMap<SkyKey?, com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(
                        128,
                        0.75f,
                        java.lang.Runtime.getRuntime().availableProcessors()
                    )
                val rootFutures: com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                    roots.stream()
                        .map<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(java.util.function.Function { l: SkyKey? ->
                            processTransitive(
                                processor,
                                l,
                                executor,
                                futureMap
                            )
                        })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>())

                val completion: com.google.common.util.concurrent.ListenableFuture<MutableList<java.lang.Void?>?> =
                    com.google.common.util.concurrent.Futures.allAsList<java.lang.Void?>(rootFutures)
                completion.get()
            }
        } catch (e: ExecutionException) {
            throw DumpFailedException("Error during traversal: " + e.getMessage(), e)
        }

        val sortedStats: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>?> =
            nodeStats.entrySet().stream()
                .parallel()
                .map<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>?>(
                    java.util.function.Function { e: MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>? ->
                        com.google.devtools.build.lib.util.Pair.of<String?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>(
                            e.getKey().getCanonicalName(),
                            e.getValue()
                        )
                    })
                .sorted(
                    java.util.Comparator.comparing<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>?, String?>(
                        java.util.function.Function { obj: com.google.devtools.build.lib.util.Pair<kotlin.String?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>? -> obj.getFirst() })
                )
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.util.MemoryAccountant.Stats?>?>())

        out.print("{")
        var first = true
        for (p in sortedStats) {
            out.printf("%s\n  %s: ", if (first) "" else ",", jsonQuote(p.getFirst()))
            val v: com.google.devtools.build.lib.util.MemoryAccountant.Stats? = p.getSecond()
            first = false
            when (displayMode) {
                com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.SUMMARY -> out.printf(
                    "{ \"objects\": %d, \"bytes\": %d }",
                    v.getObjectCount(),
                    v.getMemoryUse()
                )

                com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.COUNT -> printByClass(
                    "  ",
                    v.getObjectCountByClass(),
                    out
                )

                com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode.BYTES -> printByClass(
                    "  ",
                    v.getMemoryByClass(),
                    out
                )
            }
        }
        out.println("\n}")
    }

    companion object {
        private fun jsonQuote(s: String?): String? {
            try {
                val writer: java.io.StringWriter = java.io.StringWriter()
                val json: JsonWriter = JsonWriter(writer)
                json.value(s)
                json.flush()
                return writer.toString()
            } catch (e: IOException) {
                // StringWriter does no I/O
                throw java.lang.IllegalStateException(e)
            }
        }

        fun printByClass(prefix: String?, memory: MutableMap<String?, Long?>, out: PrintStream) {
            out.print("{")

            val sorted: com.google.common.collect.ImmutableList<MutableMap.MutableEntry<String?, Long?>> =
                memory.entrySet().stream()
                    .sorted(
                        java.util.Comparator.comparing<MutableMap.MutableEntry<String?, Long?>?, Long?>(java.util.function.Function { obj: MutableMap.MutableEntry<String?, Long?>? -> obj.getValue() })
                            .reversed()
                    )
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<MutableMap.MutableEntry<String?, Long?>?>())

            var first = true
            for (entry in sorted) {
                out.printf(
                    "%s\n%s  %s: %d", if (first) "" else ",", prefix, jsonQuote(entry.getKey()), entry.getValue()
                )
                first = false
            }

            out.printf("\n%s}", prefix)
        }

        private fun createExecutor(): ExecutorService {
            return Executors.newFixedThreadPool(
                java.lang.Runtime.getRuntime().availableProcessors(),
                com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("dump-ram-%d").build()
            )
        }
    }
}
