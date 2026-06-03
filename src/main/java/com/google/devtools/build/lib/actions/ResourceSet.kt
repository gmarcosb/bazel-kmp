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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * Instances of this class represent an estimate of the resource consumption for a particular
 * Action, or the total available resources. We plan to use this to do smarter scheduling of
 * actions, for example making sure that we don't schedule jobs concurrently if they would use so
 * much memory as to cause the machine to thrash.
 */
@Immutable
class ResourceSet private constructor(
    resources: com.google.common.collect.ImmutableMap<String?, Double?>,
    localTestCount: Int,
    workerKey: WorkerKey?
) : ResourceSetOrBuilder {
    /**
     * Map of extra resources (for example: GPUs, embedded boards, ...) mapping name of the resource
     * to a value.
     */
    private val resources: com.google.common.collect.ImmutableMap<String?, Double?>

    /** The number of local tests.  */
    private val localTestCount: Int

    /** The workerKey of used worker. Null if no worker is used.  */
    private val workerKey: WorkerKey?

    init {
        this.resources = resources
        this.localTestCount = localTestCount
        this.workerKey = workerKey
    }

    /**
     * Returns a new [ResourceSet] with the given overrides merged on top of this one's
     * resources in order. Entries in later maps replace earlier ones and this set's resources; `localTestCount` and `workerKey` are preserved. Returns `this` if all override maps
     * are empty.
     */
    @java.lang.SafeVarargs
    fun withResourceOverrides(vararg overrides: com.google.common.collect.ImmutableMap<String?, Double?>): ResourceSet {
        var anyNonEmpty = false
        for (override in overrides) {
            if (!override.isEmpty()) {
                anyNonEmpty = true
                break
            }
        }
        if (!anyNonEmpty) {
            return this
        }
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, Double?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, Double?>(resources.size)
                .putAll(resources)
        for (override in overrides) {
            builder.putAll(override)
        }
        return create(builder.buildKeepingLast(), localTestCount, workerKey)
    }

    fun get(resource: String?): Double {
        return resources.getOrDefault(resource, 0.0)
    }

    fun getMemoryMb(): Double {
        return get(MEMORY)
    }

    fun getCpuUsage(): Double {
        return get(CPU)
    }

    /**
     * Returns the workerKey of worker.
     * 
     * 
     * If there is no worker requested, then returns null
     */
    fun getWorkerKey(): WorkerKey? {
        return workerKey
    }

    fun getResources(): com.google.common.collect.ImmutableMap<String?, Double?> {
        return resources
    }

    /** Returns the local test count used.  */
    fun getLocalTestCount(): Int {
        return localTestCount
    }

    override fun toString(): String {
        return ("Resources: \n"
                + "Memory: "
                + resources.get(MEMORY)
                + "M\n"
                + "CPU: "
                + resources.get(CPU)
                + "\n"
                + resources.entries.stream()
            .filter { e: MutableMap.MutableEntry<String?, Double?>? -> e!!.key != CPU && e.key != MEMORY }
            .collect(
                java.util.function.Supplier { StringBuilder() },
                java.util.function.BiConsumer { a: java.lang.StringBuilder?, e: MutableMap.MutableEntry<String?, Double?>? ->
                    a.append(
                        e!!.key
                    ).append(": ").append(e.value).append("\n")
                },
                java.util.function.BiConsumer { obj: java.lang.StringBuilder?, s: java.lang.StringBuilder? ->
                    obj.append(
                        s
                    )
                })
                + "Local tests: "
                + localTestCount
                + "\n")
    }

    override fun equals(that: Any?): Boolean {
        if (that == null) {
            return false
        }

        if (that !is ResourceSet) {
            return false
        }

        return that.getMemoryMb() == getMemoryMb() && that.getCpuUsage() == getCpuUsage() && that.localTestCount == getLocalTestCount()
    }

    override fun hashCode(): Int {
        val p = 239
        return (com.google.common.primitives.Doubles.hashCode(getMemoryMb())
                + com.google.common.primitives.Doubles.hashCode(getCpuUsage()) * p + getLocalTestCount() * p * p)
    }

    /** Converter for [ResourceSet].  */
    class ResourceSetConverter : Contextless<ResourceSet?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String): ResourceSet {
            val values: MutableIterator<String?> = SPLITTER.split(input).iterator()
            try {
                val memoryMb: Double = values.next().toDouble()
                val cpuUsage: Double = values.next().toDouble()
                // There used to be a third field here called ioUsage. In order to not break existing users,
                // we keep expecting a third field, which must be a double. In the future, we may accept the
                // two-param variant, and then even phase out the three-param variant.
                values.next().toDouble()
                if (values.hasNext()) {
                    throw OptionsParsingException("Expected exactly 3 comma-separated float values")
                }
                if (memoryMb <= 0.0 || cpuUsage <= 0.0) {
                    throw OptionsParsingException("All resource values must be positive")
                }
                return create(memoryMb, cpuUsage, Int.Companion.MAX_VALUE)
            } catch (nfe: java.lang.NumberFormatException) {
                throw OptionsParsingException("Expected exactly 3 comma-separated float values", nfe)
            } catch (nfe: java.util.NoSuchElementException) {
                throw OptionsParsingException("Expected exactly 3 comma-separated float values", nfe)
            }
        }

        public override fun getTypeDescription(): String {
            return ("comma-separated available amount of RAM (in MB), CPU (in cores) and "
                    + "available I/O (1.0 being average workstation)")
        }

        companion object {
            private val SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on(',')
        }
    }

    @Throws(ExecException::class)
    override fun buildResourceSet(os: OS?, inputsSize: Int): ResourceSet {
        return this
    }

    companion object {
        const val CPU: String = "cpu"
        const val MEMORY: String = "memory"

        /** For actions that consume negligible resources.  */
        val ZERO: ResourceSet = ResourceSet(com.google.common.collect.ImmutableMap.of<String?, Double?>(), 0, null)

        fun createWithRamCpu(memoryMb: Double, cpu: Double): ResourceSet {
            return create(com.google.common.collect.ImmutableMap.of<String?, Double?>(MEMORY, memoryMb, CPU, cpu))
        }

        fun createWithLocalTestCount(localTestCount: Int): ResourceSet {
            return create(com.google.common.collect.ImmutableMap.of<String?, Double?>(), localTestCount)
        }

        fun create(memoryMb: Double, cpu: Double, localTestCount: Int): ResourceSet {
            return create(
                com.google.common.collect.ImmutableMap.of<String?, Double?>(MEMORY, memoryMb, CPU, cpu),
                localTestCount
            )
        }

        @kotlin.jvm.JvmOverloads
        fun create(
            resources: com.google.common.collect.ImmutableMap<String?, Double?>,
            localTestCount: Int = 0,
            workerKey: WorkerKey? = null
        ): ResourceSet {
            return ResourceSet(resources, localTestCount, workerKey)
        }
    }
}
