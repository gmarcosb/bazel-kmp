// Copyright 2020 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.clock.Clock.nanoTime
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache.put
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.eval.StarlarkSemantics
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.syntax.SyntaxError.Exception.errors
import net.starlark.java.syntax.TypeTable.errors
import java.util.TreeMap
import java.util.regex.PatternSyntaxException

// TODO(adonovan): document how to obtain a Java CPU profile.
// TODO(adonovan): mitigate the effects of JVM warmup.
// (See Oracle's JMH; we can't use it directly because it
// seems to be entirely driven by Java annotations,
// which is no good for a dynamic suite.)
/**
 * Script-based benchmarks of the Starlark evaluator.
 * 
 * 
 * Scripts in testdata/bench_*.star are executed, and then each function named `bench_*` is
 * repeatedly called and measured. The function has one parameter, b, a Benchmark, that provides
 * b.n, the number of iterations to execute. The function must have resource costs linear in b.n.
 * Typically, the function body is a loop of the form `for _ in range(b.n): ...`. Using b.n
 * for other purposes leads to meaningless results. For example, it would be a mistake to use it as
 * the length of a random list to be sorted, because sorting does not run in linear time.
 * 
 * 
 * A benchmark with significant set-up costs may reset the timer (`b.restart()`) before
 * entering its loop. Example:
 * 
 * <pre>
 * def bench_my_func(b):
 * """Description goes here."""
 * my_setup()
 * b.restart()
 * for _ in range(b.n):
 * my_func()
</pre> * 
 */
object Benchmarks {
    private val HELP: String = """
Usage: Benchmarks [--help] [--filter regex] [--seconds float] [--iterations count]

Runs Starlark benchmarks matching the filter for the specified approximate time or
specified number of iterations, and reports the following performance measures:
  ops:      number of iterations
  cpu/op:   CPU time per iteration
  wall/op:  wall time per iteration
  steps/op: Starlark computation steps per iteration
  alloc/op: approximate amount of memory allocated by the JVM per iteration
The optional filter is a regular expression applied to the string FILE:FUNC,
where FILE is the base name of the file and FUNC is the name of the function,
for example 'bench_int.star:bench_add32'.

""".trimIndent()

    private var ok = true

    @Throws(java.lang.Exception::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        var filter: java.util.regex.Pattern? = null // default: all
        var budgetNanos: Long = -1
        var iterations = -1

        // parse flags
        var i: Int
        i = 0
        while (i < args.size) {
            if (args[i] == "--") {
                i++
                break
            } else if (args[i] == "--help") {
                println(HELP)
                java.lang.System.exit(0)
            } else if (args[i] == "--filter") {
                if (++i == args.size) {
                    fail("--filter needs an argument")
                }
                try {
                    filter = java.util.regex.Pattern.compile(args[i])
                } catch (ex: PatternSyntaxException) {
                    fail("for --filter, invalid regexp: %s", ex.message)
                }
            } else if (args[i] == "--seconds") {
                if (++i == args.size) {
                    fail("--seconds needs an argument")
                }
                try {
                    budgetNanos = (1e9 * args[i].toDouble()).toLong()
                } catch (unused: java.lang.NumberFormatException) {
                    fail("for --seconds, got '%s', want floating-point number of seconds", args[i])
                }
                if (!(0 <= budgetNanos && budgetNanos <= 1e13)) {
                    fail("--seconds out of range")
                }
            } else if (args[i] == "--iterations") {
                if (++i == args.size) {
                    fail("--iterations needs an integer argument")
                }
                try {
                    iterations = args[i].toInt()
                } catch (e: java.lang.NumberFormatException) {
                    fail("for --iterations, got '%s', want an integer number of iterations", args[i])
                }
                if (iterations < 0) {
                    fail("--iterations out of range")
                }
            } else {
                fail("unknown flag: %s", args[i])
            }
            i++
        }
        if (i < args.size) {
            fail("unexpected arguments")
        }

        if (iterations >= 0 && budgetNanos >= 0) {
            fail("cannot specify both --seconds and --iterations")
        }
        if (iterations < 0 && budgetNanos < 0) {
            budgetNanos = 1000000000
        }

        // Read testdata/bench_* files.
        var src: java.io.File = java.io.File("third_party/bazel/src") // blaze
        if (!src.exists()) {
            src = java.io.File("src") // bazel
        }
        val testdata: java.io.File = java.io.File(src, "test/java/net/starlark/java/eval/testdata")
        val files: Array<java.io.File> = testdata.listFiles()
        java.util.Arrays.sort(files) // for determinism
        for (file in files) {
            val basename: String = file.getName()
            if (!(basename.startsWith("bench_") && basename.endsWith(".star"))) {
                continue
            }

            // parse & execute
            val input: net.starlark.java.syntax.ParserInput? =
                net.starlark.java.syntax.ParserInput.readFile(file.toString())
            val predeclared: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            predeclared.put("json", net.starlark.java.lib.json.Json.INSTANCE)

            val module: net.starlark.java.eval.Module =
                net.starlark.java.eval.Module.withPredeclared(semantics, predeclared.buildOrThrow())
            try {
                Mutability.create("test").use { mu ->
                    val thread: StarlarkThread = StarlarkThread.createTransient(mu, semantics)
                    Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
                }
            } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
                for (err in ex.errors()) {
                    java.lang.System.err.println(err) // includes location
                    ok = false
                    continue
                }
            } catch (ex: net.starlark.java.eval.EvalException) {
                java.lang.System.err.println(ex.getMessageWithStack())
                ok = false
                continue
            } catch (ex: Throwable) {
                // unhandled exception (incl. InterruptedException)
                java.lang.System.err.printf("in %s: %s\n", file, ex.message)
                ex.printStackTrace()
                ok = false
                continue
            }

            // Sort bench_* functions by name.
            val benchmarks: TreeMap<String?, StarlarkFunction?> = TreeMap<String?, StarlarkFunction?>()
            for (e in module.getGlobals().entries) {
                if (e.key.startsWith("bench_") && e.value is StarlarkFunction) {
                    val name: String? = e.key
                    if (filter == null || filter.matcher(basename + ":" + name).find()) {
                        benchmarks.put(name, e.value as StarlarkFunction?)
                    }
                }
            }
            if (benchmarks.isEmpty()) {
                if (filter == null) {
                    java.lang.System.err.printf("File %s: no bench_* functions\n", file)
                    ok = false
                }
                continue
            }

            // Run benchmarks.
            java.lang.System.out.printf("File %s:\n", file)
            java.lang.System.out.printf(
                "%-25s %10s %10s %10s %10s %10s\n",  //
                "benchmark", "ops", "cpu/op", "wall/op", "steps/op", "alloc/op"
            )
            for (e in benchmarks.entries) {
                val name: String? = e.key
                java.lang.System.out.flush() // help user identify a slow benchmark
                val b = Benchmark(name, e.value)
                if (!run(b, budgetNanos, iterations)) {
                    ok = false
                    continue
                }
                java.lang.System.out.printf(
                    "%-25s %10d %10s %10s %10d %10s\n",
                    name,
                    b.count,
                    formatDuration((b.cpu.toDouble()) / b.count),
                    formatDuration((b.time.toDouble()) / b.count),
                    b.steps / b.count,
                    formatBytes(b.alloc / b.count)
                )
            }
            println()
        }
        if (!ok) {
            java.lang.System.exit(1)
        }
    }

    private fun fail(format: String?, vararg args: Any?) {
        java.lang.System.err.printf(format, *args)
        java.lang.System.err.println()
        java.lang.System.exit(1)
    }

    // Runs benchmark function f for the specified time budget
    // (which we may exceed by a factor of two) or number of iterations,
    // exactly one of which must be nonnegative. Reports success.
    private fun run(b: Benchmark, budgetNanos: Long, iterations: Int): Boolean {
        // Exactly one of the parameters must be specified.
        var iterations = iterations
        com.google.common.base.Preconditions.checkState((budgetNanos >= 0) != (iterations >= 0))

        val mu: Mutability? = Mutability.create("test")
        val thread: StarlarkThread = StarlarkThread.createTransient(mu, semantics)

        // Run for a fixed number of iterations?
        if (iterations >= 0) {
            return b.runIterations(thread, iterations)
        }

        // Run for a fixed amount of time (default behavior).
        iterations = 1
        while (b.time < budgetNanos) {
            if (!b.runIterations(thread, iterations)) {
                return false
            }

            // Keep doubling the number of iterations until we exceed the deadline.
            // TODO(adonovan): opt: extrapolate and predict the number of iterations
            // in the remaining time budget, being wary of extrapolation error.
            iterations = iterations shl 1
            if (iterations <= 0) { // overflow
                java.lang.System.err.printf(
                    "In %s: function is too fast; likely a loop over `range(b.n)` is missing\n", b.name
                )
                return false
            }
        }
        return true
    }

    private fun formatDuration(ns: Double): String? {
        // (Similar format to Go's time.Duration.)
        if (ns == 0.0) {
            return "0s"
        } else if (ns < 1e3) {
            return String.format("%dns", ns.toLong())
        } else if (ns < 1e6) {
            return String.format("%.3gµs", ns / 1e3)
        } else if (ns < 1e9) {
            return String.format("%.6gms", ns / 1e6)
        } else {
            return String.format("%.3gs", ns / 1e9)
        }
    }

    private fun formatBytes(bytes: Long): String? {
        if (bytes == 0L) {
            return "0B"
        } else if (bytes < 1e3) {
            return String.format("%dB", bytes)
        } else if (bytes < 1e6) {
            return String.format("%.3gKB", bytes / 1e3)
        } else if (bytes < 1e9) {
            return String.format("%.6gMB", bytes / 1e6)
        } else {
            return String.format("%.3gGB", bytes / 1e9)
        }
    }

    private val semantics: StarlarkSemantics? = StarlarkSemantics.DEFAULT

    // The type of the parameter to each bench(b) function.
    // Provides n, the number of iterations.
    @StarlarkBuiltin(name = "Benchmark")
    private class Benchmark(private val name: String?, f: StarlarkFunction?) : StarlarkValue {
        private val f: StarlarkFunction?

        // The cast assumes we use the "Sun" JVM, which measures per-thread allocation and CPU.
        private val threadMX: com.sun.management.ThreadMXBean =
            java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

        // Starlark attributes
        private var n = 0 // requested number of iterations

        // current span  (time0 != 0 => started)
        private var cpu0: Long = 0
        private var alloc0: Long = 0
        private var time0: Long = 0
        private var steps0: Long = 0

        // accumulators
        private var count = 0 // iterations
        private var cpu: Long = 0 // CPU time (ns) in this thread
        private var alloc: Long = 0 // bytes allocated by this thread
        private var time: Long = 0 // wall time (ns)
        private var steps: Long = 0 // Starlark computation steps

        init {
            this.f = f
        }

        // Runs n iterations of this benchmark and reports success.
        fun runIterations(thread: StarlarkThread, n: Int): Boolean {
            this.n = n
            try {
                start(thread)
                Starlark.positionalOnlyCall(thread, f, this)
                stop(thread)
                this.count += n
            } catch (ex: net.starlark.java.eval.EvalException) {
                java.lang.System.err.println(ex.getMessageWithStack())
                return false
            } catch (ex: Throwable) {
                // unhandled exception (incl. InterruptedException)
                java.lang.System.err.printf("In %s: %s\n", name, ex.message)
                ex.printStackTrace()
                return false
            }
            return true
        }

        @StarlarkMethod(name = "n", doc = "Requested number of iterations.", structField = true)
        fun n(): Int {
            return n
        }

        @StarlarkMethod(name = "start", doc = "Starts the timer.", useStarlarkThread = true)
        @Throws(net.starlark.java.eval.EvalException::class)
        fun start(thread: StarlarkThread) {
            if (time0 != 0L) {
                throw Starlark.errorf("timer already started")
            }

            this.cpu0 = threadMX.getCurrentThreadCpuTime()
            this.alloc0 = threadMX.getThreadAllocatedBytes(java.lang.Thread.currentThread().getId())
            this.steps0 = thread.executedSteps
            this.time0 = java.lang.System.nanoTime()
        }

        @StarlarkMethod(name = "stop", doc = "Starts the timer.", useStarlarkThread = true)
        @Throws(net.starlark.java.eval.EvalException::class)
        fun stop(thread: StarlarkThread) {
            if (time0 == 0L) {
                throw Starlark.errorf("timer already stopped")
            }
            val time1: Long = java.lang.System.nanoTime()
            val steps1: Long = thread.executedSteps
            val alloc1: Long = threadMX.getThreadAllocatedBytes(java.lang.Thread.currentThread().getId())
            val cpu1: Long = threadMX.getCurrentThreadCpuTime()

            this.time += time1 - this.time0
            this.steps += steps1 - this.steps0
            this.alloc += alloc1 - this.alloc0
            this.cpu += cpu1 - this.cpu0

            time0 = 0 // stopped
        }

        @StarlarkMethod(name = "restart", doc = "Restarts the timer.", useStarlarkThread = true)
        @Throws(net.starlark.java.eval.EvalException::class)
        fun restart(thread: StarlarkThread) {
            time0 = 0 // stop, and discard current span
            start(thread)
        }

        override fun repr(p: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            p.append("<Benchmark>")
        }
    }
}
