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
package com.google.devtools.build.lib.profiler.memory

import com.google.devtools.build.lib.concurrent.ThreadSafety.ConditionallyThreadCompatible

/** Tracks allocations for memory reporting.  */
@ConditionallyThreadCompatible
// the AllocationTracker is effectively a global
class AllocationTracker internal constructor(samplePeriod: Int, variance: Int) :
    com.google.monitoring.runtime.instrumentation.Sampler, net.starlark.java.eval.Debug.ThreadHook {
    // A mapping from Java thread to StarlarkThread.
    // Used to effect a hidden StarlarkThread parameter to sampleAllocation.
    // TODO(adonovan): opt: merge the three different ThreadLocals in use here.
    private val starlarkThread: java.lang.ThreadLocal<net.starlark.java.eval.StarlarkThread?> =
        java.lang.ThreadLocal<net.starlark.java.eval.StarlarkThread?>()

    override fun onPushFirst(thread: net.starlark.java.eval.StarlarkThread?) {
        starlarkThread.set(thread)
    }

    override fun onPopLast(thread: net.starlark.java.eval.StarlarkThread?) {
        starlarkThread.remove()
    }

    private class AllocationSample(
        ruleClass: RuleClass?,
        aspectClass: AspectClass?,
        callstack: com.google.common.collect.ImmutableList<Frame?>,
        bytes: Long
    ) {
        val ruleClass: RuleClass? // Current rule being analysed, if any
        val aspectClass: AspectClass? // Current aspect being analysed, if any
        val callstack: com.google.common.collect.ImmutableList<Frame?> // Starlark callstack, if any
        val bytes: Long

        init {
            this.ruleClass = ruleClass
            this.aspectClass = aspectClass
            this.callstack = callstack
            this.bytes = bytes
        }
    }

    private class Frame(name: String?, loc: net.starlark.java.syntax.Location, ruleFunction: RuleFunction?) {
        val name: String?
        val loc: net.starlark.java.syntax.Location
        val ruleFunction: RuleFunction?

        init {
            this.name = name
            this.loc = loc
            this.ruleFunction = ruleFunction
        }
    }

    private val allocations: com.github.benmanes.caffeine.cache.Cache<Any?, AllocationSample> =
        Caffeine.newBuilder().weakKeys().build<Any?, AllocationSample?>()
    private val samplePeriod: Int
    private val sampleVariance: Int
    private var enabled = true

    /**
     * Cheap wrapper class for a long. Avoids having to do two thread-local lookups per allocation.
     */
    private class LongValue {
        var value: Long = 0
    }

    private val currentSampleBytes: java.lang.ThreadLocal<LongValue> =
        java.lang.ThreadLocal.withInitial<LongValue?>(java.util.function.Supplier { com.google.devtools.build.lib.profiler.memory.AllocationTracker.LongValue() })
    private val nextSampleBytes: java.lang.ThreadLocal<Long?> =
        java.lang.ThreadLocal.withInitial<Long?>(java.util.function.Supplier { this.getNextSample() })
    private val random: Random = Random()

    init {
        this.samplePeriod = samplePeriod
        this.sampleVariance = variance
    }

    // Called by instrumentation.recordAllocation, which is in turn called
    // by an instrumented version of the application assembled on the fly
    // by instrumentation.AllocationInstrumenter.
    // The instrumenter inserts a call to recordAllocation after every
    // memory allocation instruction in the original class.
    //
    // This function runs within 'new', so is not supposed to allocate memory;
    // see Sampler interface. In fact it allocates in nearly a dozen places.
    // TODO(adonovan): suppress reentrant calls by setting a thread-local flag.
    @ThreadSafe
    override fun sampleAllocation(count: Int, desc: String?, newObj: Any?, size: Long) {
        if (!enabled) {
            return
        }

        val thread: net.starlark.java.eval.StarlarkThread? = starlarkThread.get()

        // Calling Debug.getCallStack is a dubious operation here.
        // First it allocates memory, which breaks the Sampler contract.
        // Second, the allocation could in principle occur while the thread's
        // representation invariants are temporarily broken (that is, during
        // the call to ArrayList.add when pushing a new stack frame).
        // For now at least, the allocation done by ArrayList.add occurs before
        // the representation of the ArrayList is changed, so it is safe,
        // but this is a fragile assumption.
        val callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.Debug.Frame> =
            if (thread != null) net.starlark.java.eval.Debug.getCallStack(thread) else com.google.common.collect.ImmutableList.of<net.starlark.java.eval.Debug.Frame?>()

        val ruleClass: RuleClass? = CurrentRuleTracker.getRule()
        val aspectClass: AspectClass? = CurrentRuleTracker.getAspect()

        // Should we bother sampling?
        if (callstack.isEmpty() && ruleClass == null && aspectClass == null) {
            return
        }

        // Convert the thread's stack right away to our internal form.
        // It is not safe to inspect Debug.Frame references once the thread resumes,
        // and keeping StarlarkCallable values live defeats garbage collection.
        val frames: com.google.common.collect.ImmutableList.Builder<Frame?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<Frame?>(callstack.size())
        for (fr in callstack) {
            // The frame's PC location is currently not updated at every step,
            // only at function calls, so the leaf frame's line number may be
            // slightly off; see the tests.
            // TODO(b/149023294): remove comment when we move to a compiled representation.
            val fn: net.starlark.java.eval.StarlarkCallable = fr.getFunction()
            frames.add(
                com.google.devtools.build.lib.profiler.memory.AllocationTracker.Frame(
                    fn.getName(),
                    fr.getLocation(),
                    if (fn is RuleFunction) fn as RuleFunction else null
                )
            )
        }

        // If we start getting stack overflows here, it's because the memory sampling
        // implementation has changed to call back into the sampling method immediately on
        // every allocation. Since thread locals can allocate, this can in this case lead
        // to infinite recursion. This method will then need to be rewritten to not
        // allocate, or at least not allocate to obtain its sample counters.
        val bytesValue: LongValue = currentSampleBytes.get()
        val bytes = bytesValue.value + size
        if (bytes < nextSampleBytes.get()) {
            bytesValue.value = bytes
            return
        }
        bytesValue.value = 0
        nextSampleBytes.set(getNextSample())
        allocations.put(newObj, AllocationSample(ruleClass, aspectClass, frames.build(), bytes))
    }

    private fun getNextSample(): Long {
        return samplePeriod.toLong() + (if (sampleVariance > 0) (random.nextInt(sampleVariance * 2) - sampleVariance) else 0)
    }

    /** A pair of rule/aspect name and the bytes it consumes.  */
    class RuleBytes(name: String?) {
        private val name: String?
        @kotlin.jvm.JvmField
        private var bytes: Long = 0

        init {
            this.name = name
        }

        /** The number of bytes total occupied by this rule or aspect class.  */
        fun getBytes(): Long {
            return bytes
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBytes(bytes: Long): RuleBytes {
            this.bytes += bytes
            return this
        }

        override fun toString(): String {
            return java.lang.String.format("RuleBytes(%s, %d)", name, bytes)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }
            val ruleBytes = o as RuleBytes
            return bytes == ruleBytes.bytes && com.google.common.base.Objects.equal(name, ruleBytes.name)
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(name, bytes)
        }
    }

    /**
     * Returns the total memory consumption for rules and aspects, keyed by [RuleClass.getKey]
     * or [AspectClass.getKey].
     */
    fun getRuleMemoryConsumption(
        rules: MutableMap<String?, RuleBytes>, aspects: MutableMap<String?, RuleBytes>
    ) {
        // Make sure we don't track our own allocations
        enabled = false
        java.lang.System.gc()

        // Get loading phase memory for rules.
        for (sample in allocations.asMap().values()) {
            val rule: RuleFunction? = getRule(sample)
            if (rule != null) {
                val ruleClass: RuleClass = rule.getRuleClass()
                val key: String? = ruleClass.getKey()
                val ruleBytes: RuleBytes = rules.computeIfAbsent(
                    key,
                    java.util.function.Function { k: String? -> RuleBytes(ruleClass.getName()) })
                rules.put(key, ruleBytes.addBytes(sample.bytes))
            }
        }
        // Get analysis phase memory for rules and aspects
        for (sample in allocations.asMap().values()) {
            if (sample.ruleClass != null) {
                val key: String? = sample.ruleClass.getKey()
                val ruleBytes: RuleBytes =
                    rules.computeIfAbsent(
                        key,
                        java.util.function.Function { k: String? -> RuleBytes(sample.ruleClass.getName()) })
                rules.put(key, ruleBytes.addBytes(sample.bytes))
            }
            if (sample.aspectClass != null) {
                val key: String? = sample.aspectClass.getKey()
                val ruleBytes: RuleBytes =
                    aspects.computeIfAbsent(
                        key,
                        java.util.function.Function { k: String? -> RuleBytes(sample.aspectClass.getName()) })
                aspects.put(key, ruleBytes.addBytes(sample.bytes))
            }
        }

        enabled = true
    }

    /** Dumps all Starlark analysis time allocations to a pprof-compatible file.  */
    @Throws(IOException::class)
    fun dumpStarlarkAllocations(outputStream: java.io.OutputStream) {
        // Make sure we don't track our own allocations
        enabled = false
        java.lang.System.gc()
        val profile: Profile = buildMemoryProfile()
        GZIPOutputStream(outputStream).use { gzipOutputStream ->
            profile.writeTo(gzipOutputStream)
            gzipOutputStream.finish()
        }
        enabled = true
    }

    fun buildMemoryProfile(): Profile {
        val profile: Profile.Builder = Profile.newBuilder()
        val stringTable: StringTable =
            com.google.devtools.build.lib.profiler.memory.AllocationTracker.StringTable(profile)
        val functionTable: FunctionTable =
            com.google.devtools.build.lib.profiler.memory.AllocationTracker.FunctionTable(profile, stringTable)
        val locationTable = LocationTable(profile, functionTable)
        profile.addSampleType(
            ValueType.newBuilder()
                .setType(stringTable.get("memory"))
                .setUnit(stringTable.get("bytes"))
                .build()
        )
        for (sample in allocations.asMap().values()) {
            // Skip empty callstacks
            if (sample.callstack.isEmpty()) {
                continue
            }
            val b: Sample.Builder = Sample.newBuilder().addValue(sample.bytes)
            for (fr in sample.callstack.reverse()) {
                b.addLocationId(locationTable.get(fr.loc.file(), fr.name, fr.loc.line().toLong()))
            }
            profile.addSample(b.build())
        }
        profile.setTimeNanos(Instant.now().getEpochSecond() * 1000000000)
        return profile.build()
    }

    private class StringTable(profile: Profile.Builder) {
        val profile: Profile.Builder
        val table: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        var index: Long = 0

        init {
            this.profile = profile
            get("") // 0 is reserved for the empty string
        }

        fun get(str: String?): Long {
            return table.computeIfAbsent(
                str,
                java.util.function.Function { key: String? ->
                    profile.addStringTable(key)
                    index++
                })
        }
    }

    private class FunctionTable(profile: Profile.Builder, stringTable: StringTable) {
        val profile: Profile.Builder
        val stringTable: StringTable
        val table: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        var index: Long = 1 // 0 is reserved

        init {
            this.profile = profile
            this.stringTable = stringTable
        }

        fun get(file: String?, function: String?): Long {
            return table.computeIfAbsent(
                file + "#" + function,
                java.util.function.Function { key: String? ->
                    val fn: Function? =
                        Function.newBuilder()
                            .setId(index)
                            .setFilename(stringTable.get(file))
                            .setName(stringTable.get(function))
                            .build()
                    profile.addFunction(fn)
                    index++
                })
        }
    }

    private class LocationTable(profile: Profile.Builder, functionTable: FunctionTable) {
        val profile: Profile.Builder
        val functionTable: FunctionTable
        val table: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        var index: Long = 1 // 0 is reserved

        init {
            this.profile = profile
            this.functionTable = functionTable
        }

        fun get(file: String?, function: String?, line: Long): Long {
            return table.computeIfAbsent(
                file + "#" + function + "#" + line,
                java.util.function.Function { key: String? ->
                    val location: com.google.perftools.profiles.ProfileProto.Location? =
                        com.google.perftools.profiles.ProfileProto.Location.newBuilder()
                            .setId(index)
                            .addLine(
                                Line.newBuilder()
                                    .setFunctionId(functionTable.get(file, function))
                                    .setLine(line)
                                    .build()
                            )
                            .build()
                    profile.addLocation(location)
                    index++
                })
        }
    }

    companion object {
        // If the topmost stack entry is a call to a rule function, returns it.
        private fun getRule(sample: AllocationSample): RuleFunction? {
            val top: Frame? = com.google.common.collect.Iterables.getLast<Frame?>(sample.callstack, null)
            return if (top != null) top.ruleFunction else null
        }
    }
}
