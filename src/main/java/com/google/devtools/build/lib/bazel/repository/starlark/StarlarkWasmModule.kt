// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.dylibso.chicory.compiler.InterpreterFallback
import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import com.google.common.collect.ImmutableList
import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.*
import java.io.IOException
import java.lang.String
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.*
import java.util.function.Function
import java.util.function.Supplier
import kotlin.Any
import kotlin.ArithmeticException
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Int
import kotlin.Long
import kotlin.LongArray

@ThreadSafety.Immutable
@StarlarkBuiltin(
    name = "wasm_module",
    category = DocCategory.BUILTIN,
    doc = "A WebAssembly module loaded by <code>repository_ctx.load_wasm()</code>."
)
internal class StarlarkWasmModule(
    path: StarlarkPath,
    origPath: Any?,
    moduleContent: ByteArray,
    compile: Boolean,
    allocFnName: String?
) : StarlarkValue {
    @ThreadSafety.ThreadSafe
    internal class StarlarkWasmCompilationCache : com.dylibso.chicory.compiler.Cache {
        private val cache: Cache<String?, ByteArray?>

        init {
            this.cache =
                Caffeine.newBuilder()
                    .maximumSize(CACHE_MAX_SIZE.toLong())
                    .expireAfterAccess(CACHE_DURATION)
                    .scheduler(Scheduler.systemScheduler())
                    .build<String?, ByteArray?>()
        }

        @Throws(IOException::class)
        override fun get(key: String?): ByteArray? {
            return cache.getIfPresent(key)
        }

        @Throws(IOException::class)
        override fun putIfAbsent(key: String?, data: ByteArray?) {
            cache.asMap().putIfAbsent(key, data)
        }

        companion object {
            private const val CACHE_MAX_SIZE = 1000
            private val CACHE_DURATION: Duration = Duration.ofMinutes(15)
        }
    }

    val path: StarlarkPath?

    @get:StarlarkMethod(
        name = "path",
        structField = true,
        doc = "The path this WebAssembly module was loaded from."
    )
    val origPath: Any?
    private val wasmModule: WasmModule
    private val allocFnName: String?
    private val hasInitializeFn: Boolean
    private val machineFactory: Function<Instance?, Machine?>

    init {
        val wasmModule: WasmModule
        Profiler.instance().profile(ProfilerTask.WASM_LOAD, Supplier { "load " + path.toString() }).use { c1 ->
            Profiler.instance().profile(ProfilerTask.WASM_LOAD, "parse").use { c2 ->
                try {
                    wasmModule = Parser.parse(moduleContent)
                } catch (e: ChicoryException) {
                    throw EvalException(e)
                }
            }
            validateModule(wasmModule, allocFnName)
        }
        this.path = path
        this.origPath = origPath
        this.wasmModule = wasmModule
        this.allocFnName = allocFnName
        this.hasInitializeFn = hasInitializeFn(wasmModule)
        if (compile) {
            this.machineFactory = MachineFactoryCompiler.builder(wasmModule)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .withCache(compilationCache)
                .compile()
        } else {
            this.machineFactory = Function { instance: Instance? -> InterpreterMachine(instance) }
        }
    }

    override fun isImmutable(): Boolean {
        return true
    }

    override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append("<wasm_module path=")
        printer.repr(origPath, semantics)
        printer.append(" allocate_fn=")
        printer.repr(allocFnName, semantics)
        printer.append(">")
    }

    @Throws(EvalException::class, InterruptedException::class)
    fun execute(
        execFnName: String?, input: ByteArray, timeout: Duration, memLimitBytes: Long
    ): StarlarkWasmExecutionResult? {
        Profiler.instance().profile(ProfilerTask.WASM_EXEC, Supplier { "execute " + execFnName }).use { c ->
            val memLimits = getMemLimits(memLimitBytes)
            // Perform initialization and execution in a separate thread so it can be interrupted
            // in case of timeout.
            val wasmThreadFactory =
                Thread.ofPlatform().name(Thread.currentThread().getName() + "_wasm").factory()
            var result: StarlarkWasmExecutionResult?
            val errMessage: String?
            try {
                Executors.newSingleThreadExecutor(wasmThreadFactory).use { executor ->
                    return executor.invokeAny<StarlarkWasmExecutionResult?>(
                        ImmutableList.of<Callable<StarlarkWasmExecutionResult?>?>(Callable {
                            run(
                                execFnName,
                                input,
                                memLimits
                            )
                        }),
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                }
            } catch (e: TimeoutException) {
                errMessage = String.format("Error executing %s: timed out", execFnName)
            } catch (e: ExecutionException) {
                errMessage = String.format("Error executing %s: %s", execFnName, e.getCause().getMessage())
            }
            return StarlarkWasmExecutionResult.Companion.newErr(errMessage)
        }
    }

    @Throws(EvalException::class, InterruptedException::class)
    private fun run(
        execFnName: kotlin.String?,
        input: ByteArray,
        memLimits: MemoryLimits?
    ): StarlarkWasmExecutionResult {
        val instance: Instance?
        try {
            instance =
                Instance.builder(wasmModule)
                    .withMachineFactory(machineFactory)
                    .withMemoryLimits(memLimits) // Disable calling `_start()`, which is the entry point for WASI-style
                    // command modules.
                    .withStart(false) // Chicory documentation recommends ByteArrayMemory for OpenJDK
                    // https://chicory.dev/docs/advanced/memory
                    .withMemoryFactory(Function { limits: MemoryLimits? -> ByteArrayMemory(limits) })
                    .build()
            // If `_initialize()` is present then call it to perform early setup.
            //
            // Note: The WebAssembly spec describes a "start function", named in a
            // "start section", that is to be called as part of module initialization.
            // Actual implementations such as LLVM have instead used the start function
            // as the equivalent of a native binary's entry point, and expect (or emit)
            // a function named `_initialize` to be used for early initialization.
            //
            // For additional context, see:
            // - https://bugs.llvm.org/show_bug.cgi?id=37198
            // - https://reviews.llvm.org/D40559
            // - https://github.com/WebAssembly/design/issues/1160
            if (hasInitializeFn) {
                Profiler.instance().profile(ProfilerTask.WASM_EXEC, "initialize").use { c ->
                    instance.export("_initialize").apply()
                }
            }
        } catch (e: ChicoryException) {
            throw EvalException(e)
        }

        val memory = instance.memory()
        val allocFn = instance.export(allocFnName)
        // TODO: #26092 - Is this check needed? Might be redundant with validateModule().
        if (allocFn == null) {
            throw Starlark.errorf("WebAssembly module doesn't export \"%s\"", allocFnName)
        }
        val execFn = instance.export(execFnName)
        // TODO: #26092 - Validate execFn has the expected signature?
        if (execFn == null) {
            throw Starlark.errorf("WebAssembly module doesn't export \"%s\"", execFnName)
        }

        val inputLen = Math.toIntExact(input.size.toLong())
        val inputPtr: Int = alloc(allocFnName, allocFn, inputLen, 1)
        Profiler.instance().profile(ProfilerTask.WASM_EXEC, "copy input").use { c ->
            memory.write(inputPtr, input)
        }
        // struct { output_ptr_ptr: **u8, output_len_ptr: *u32 }
        val paramsPtr: Int = alloc(allocFnName, allocFn, 8, 4)
        val outputPtrPtr = paramsPtr
        val outputLenPtr = paramsPtr + 4
        memory.writeI32(outputPtrPtr, 0)
        memory.writeI32(outputLenPtr, 0)

        val execResult: LongArray
        Profiler.instance().profile(ProfilerTask.WASM_EXEC, "execute").use { c ->
            execResult =
                execFn.apply(inputPtr.toLong(), inputLen.toLong(), outputPtrPtr.toLong(), outputLenPtr.toLong())
        }
        // TODO: #26092 - Not 100% sure this check is necessary, but the ambiguity between
        // signed/unsigned in Java vs WebAssembly makes me nervous.
        //
        // Might be unnecessary if the function signature is verified before execution?
        var returnCode = execResult[0]
        if (returnCode < 0 || returnCode > 0xFFFFFFFFL) {
            returnCode = 0xFFFFFFFFL
        }
        val outputPtr = memory.readInt(outputPtrPtr)
        val outputLen = memory.readInt(outputLenPtr)

        var output = ""
        if (outputLen > 0) {
            Profiler.instance().profile(ProfilerTask.WASM_EXEC, "copy output").use { c ->
                val outputBytes = memory.readBytes(outputPtr, outputLen)
                output = kotlin.String(outputBytes, StandardCharsets.ISO_8859_1)
            }
        }
        return StarlarkWasmExecutionResult.Companion.newOk(returnCode, output)
    }

    @Throws(EvalException::class)
    fun getMemLimits(memLimitBytes: Long): MemoryLimits {
        var initialPages = 1
        val memLimitPages: Int = getMemLimitPages(memLimitBytes)

        if (wasmModule.memorySection().isPresent()) {
            val memories = wasmModule.memorySection().get()
            val memoryCount = memories.memoryCount()
            if (memoryCount > 1) {
                // TODO: #26092 - Figure out what memory limits mean when applied to
                // a WebAssembly module with multiple memories.
                throw Starlark.errorf("WebAssembly modules with multiple memories not yet supported")
            }
            if (memoryCount != 0) {
                val limits = memories.getMemory(0).limits()
                if (limits.initialPages() > initialPages) {
                    initialPages = limits.initialPages()
                }
            }
        }
        if (initialPages > memLimitPages) {
            // TODO: #26092 - Should probably throw an exception. The execution will likely fail anyway,
            // and
            // throwing an exception from this point would provide more relevant details.
            initialPages = memLimitPages
        }
        return MemoryLimits(initialPages, memLimitPages)
    }

    companion object {
        private val compilationCache = StarlarkWasmCompilationCache()

        private fun hasInitializeFn(wasmModule: WasmModule): Boolean {
            val exports = wasmModule.exportSection()
            val exportCount = exports.exportCount()
            for (ii in 0..<exportCount) {
                if (exports.getExport(ii).name() == "_initialize") {
                    return true
                }
            }
            return false
        }

        @Throws(EvalException::class)
        private fun validateModule(wasmModule: WasmModule, allocFnName: kotlin.String?) {
            val exports = wasmModule.exportSection()
            val exportCount = exports.exportCount()
            for (ii in 0..<exportCount) {
                val export = exports.getExport(ii)
                if (export.name() == allocFnName) {
                    // TODO: #26092 - Validate exported type is a function and has the expected signature?
                    return
                }
            }
            throw Starlark.errorf("WebAssembly module doesn't contain an export named \"%s\"", allocFnName)
        }

        fun getMemLimitPages(memLimitBytes: Long): Int {
            if (memLimitBytes == 0L) {
                return 1
            }
            return Math.min(MemoryLimits.MAX_PAGES.toLong(), Math.ceilDiv(memLimitBytes, Memory.PAGE_SIZE)).toInt()
        }

        @Throws(ChicoryException::class, EvalException::class)
        fun alloc(allocFnName: kotlin.String?, allocFn: ExportFunction, size: Int, align: Int): Int {
            val allocResult = allocFn.apply(size.toLong(), align.toLong())
            val ptr = allocResult[0]
            if (ptr == 0L) {
                throw Starlark.errorf(
                    "allocation failed: %s(%d, %d) returned NULL", allocFnName, size, align
                )
            }
            try {
                return Math.toIntExact(ptr)
            } catch (e: ArithmeticException) {
                throw Starlark.errorf(
                    "allocation failed: %s(%d, %d) returned invalid pointer 0x%08X (out of range)",
                    allocFnName, size, align, ptr
                )
            }
        }
    }
}
