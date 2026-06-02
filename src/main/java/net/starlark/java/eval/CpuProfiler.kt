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

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.clock.Clock.currentTimeMillis
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.io.FileInputStream
import java.io.IOException
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

// Overview
//
// A CPU profiler measures CPU cycles consumed by each thread.
// It does not account for time a thread is blocked in I/O
// (e.g. within a call to glob), or runnable but not actually
// running, as happens when there are more runnable threads than cores.
//
// CPU profiling requires operating system support.
// On POSIX systems, the setitimer system call causes
// the kernel to signal an application periodically.
// With the ITIMER_PROF option, setitimer delivers a
// SIGPROF signal to a running thread each time its CPU usage
// exceeds the specific quantum. A profiler builds a histogram
// of these these signals, grouped by the current program
// counter location, or more usefully by the complete stack of
// program counter locations.
//
// This profiler calls a C++ function to install a SIGPROF handler.
// Like all handlers for asynchronous signals (that is, signals not
// caused by the execution of program instructions), it is extremely
// constrained in what it may do. It cannot acquire locks, allocate
// memory, or interact with the JVM in any way. Our signal handler
// simply sends a message into a global pipe; the message records
// the operating system's identifier (tid) for the signalled thread.
//
// Reading from the other end of the pipe is a Java thread, the router.
// Its job is to map each OS tid to a StarlarkThread, if the
// thread is currently executing Starlark code, and increment
// a volatile counter in that StarlarkThread. If the thread is
// not executing Starlark code, the router discards the event.
// When a Starlark thread enters or leaves a function during profiling,
// it updates the StarlarkThread-to-OS-thread mapping consulted by the
// router.
//
// If the router does not drain the pipe in a timely manner (on the
// order of 10s; see signal handler), the signal handler prints a
// warning and discards the event.
//
// The router may induce a delay between the kernel signal and the
// thread's stack sampling, during which Starlark execution may have
// moved on to another function. Assuming uniform delay, this is
// equivalent to shifting the phase but not the frequency of CPU ticks.
// Nonetheless it may bias the profile because, for example,
// it would cause a Starlark 'sleep' function to accrue a nonzero
// number of CPU ticks that properly belong to the preceding computation.
//
// When a Starlark thread leaves any function, it reads and clears
// its counter of CPU ticks. If the counter was nonzero, the thread
// writes a copy of its stack to the profiler log in pprof form,
// which is a gzip-compressed stream of protocol messages.
//
// The profiler is inherently global to the process,
// and records the effects of all Starlark threads.
// It may be started and stopped concurrent with Starlark execution,
// allowing profiling of a portion of a long-running computation.
/** A CPU profiler for Starlark (POSIX only for now).  */
class CpuProfiler private constructor(out: java.io.OutputStream, period: java.time.Duration) {
    private val pprof: PprofWriter

    /** Records a profile event.  */
    fun addEvent(ticks: Int, stack: MutableList<out net.starlark.java.eval.Debug.Frame>) {
        pprof.writeEvent(ticks, stack)
    }

    init {
        this.pprof = net.starlark.java.eval.CpuProfiler.PprofWriter(out, period)
    }

    // Encoder for pprof format profiles.
    // See https://github.com/google/pprof/tree/master/proto
    // We encode the protocol messages by hand to avoid
    // adding a dependency on the protocol compiler.
    private class PprofWriter(out: java.io.OutputStream, period: java.time.Duration) {
        private val period: java.time.Duration
        private val startNano: Long
        private val gz: GZIPOutputStream? = null
        private var error: IOException? = null // the first write error, if any; reported during stop()

        @kotlin.jvm.Synchronized
        fun writeEvent(ticks: Int, stack: MutableList<out net.starlark.java.eval.Debug.Frame>) {
            if (this.error == null) {
                try {
                    val sample: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                        sample,
                        net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.SAMPLE_VALUE,
                        ticks * period.toNanos() / 1000L
                    )
                    for (fr in stack.reversed()) {
                        net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                            sample,
                            net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.SAMPLE_LOCATION_ID,
                            getLocationID(fr)
                        )
                    }
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                        gz,
                        net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_SAMPLE,
                        sample.toByteArray()
                    )
                } catch (ex: IOException) {
                    this.error = ex
                }
            }
        }

        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        fun writeEnd() {
            val endNano: Long = java.lang.System.nanoTime()
            try {
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_DURATION_NANOS,
                    endNano - startNano
                )
                if (this.error != null) {
                    throw this.error // retained from an earlier error
                }
            } finally {
                gz.close()
            }
        }

        // Every string, function, and PC location is emitted once
        // and thereafter referred to by its identifier, a Long.
        private val stringIDs: MutableMap<String?, Long?> = HashMap<String?, Long?>()
        private val functionIDs: MutableMap<Long?, Long?> = HashMap<Long?, Long?>() // key is "address" of function
        private val locationIDs: MutableMap<Long?, Long?> = HashMap<Long?, Long?>() // key is "address" of PC location

        init {
            this.period = period
            this.startNano = java.lang.System.nanoTime()

            try {
                this.gz = GZIPOutputStream(out)
                getStringID("") // entry 0 is always ""

                // dimension and unit
                val unit: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    unit,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.VALUETYPE_TYPE,
                    getStringID("CPU")
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    unit,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.VALUETYPE_UNIT,
                    getStringID("microseconds")
                )

                // informational fields of Profile
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_SAMPLE_TYPE,
                    unit.toByteArray()
                )
                // magnitude of sampling period:
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_PERIOD,
                    period.toNanos() / 1000L
                )
                // dimension and unit of period:
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_PERIOD_TYPE,
                    unit.toByteArray()
                )
                // start (real) time of profile:
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_TIME_NANOS,
                    java.lang.System.currentTimeMillis() * 1000000L
                )
            } catch (ex: IOException) {
                this.error = ex
            }
        }

        // Returns the ID of the specified string,
        // emitting a pprof string record the first time it is encountered.
        @Throws(IOException::class)
        fun getStringID(s: String): Long {
            val i: Long? = stringIDs.putIfAbsent(s, java.lang.Long.valueOf(stringIDs.size().toLong()))
            if (i == null) {
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeString(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_STRING_TABLE,
                    s
                )
                return stringIDs.size() - 1L
            }
            return i
        }

        // Returns the ID of a StarlarkCallable for use in Line.FunctionId,
        // emitting a pprof Function record the first time fn is encountered.
        // The ID is the same as the function's logical address,
        // which is supplied by the caller to avoid the need to recompute it.
        @Throws(IOException::class)
        fun getFunctionID(fn: net.starlark.java.eval.StarlarkCallable, addr: Long): Long {
            var id = functionIDs.get(addr)
            if (id == null) {
                id = addr

                val loc: net.starlark.java.syntax.Location = fn.getLocation()
                val filename: String = loc.file() // TODO(adonovan): make WORKSPACE-relative
                var name: String = fn.getName()
                if (name == net.starlark.java.eval.StarlarkThread.Companion.TOP_LEVEL) {
                    name = filename
                }

                val nameID = getStringID(name)

                val `fun`: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    `fun`,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.FUNCTION_ID,
                    id
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    `fun`,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.FUNCTION_NAME,
                    nameID
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    `fun`,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.FUNCTION_SYSTEM_NAME,
                    nameID
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    `fun`,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.FUNCTION_FILENAME,
                    getStringID(filename)
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    `fun`,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.FUNCTION_START_LINE,
                    loc.line().toLong()
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_FUNCTION,
                    `fun`.toByteArray()
                )

                functionIDs.put(addr, id)
            }
            return id
        }

        // Returns the ID of the location denoted by fr,
        // emitting a pprof Location record the first time it is encountered.
        // For Starlark frames, this is the Frame pc.
        @Throws(IOException::class)
        fun getLocationID(fr: net.starlark.java.eval.Debug.Frame): Long {
            val fn: net.starlark.java.eval.StarlarkCallable = fr.getFunction()
            // fnAddr identifies a function as a whole.
            val fnAddr: Int = java.lang.System.identityHashCode(fn) // very imperfect

            // pcAddr identifies the current program point.
            //
            // For now, this is the same as fnAddr, because
            // we don't track the syntax node currently being
            // evaluated. Statement-level profile information
            // in the leaf function (displayed by 'pprof list <fn>')
            // is thus unreliable for now.
            val pcAddr = fnAddr.toLong()
            if (fn is net.starlark.java.eval.StarlarkFunction) {
                // TODO(adonovan): when we use a byte code representation
                // of function bodies, mix the program counter fr.pc into fnAddr.
                // TODO(adonovan): even cleaner: treat each function's byte
                // code segment as its own Profile.Mapping, indexed by pc.
                //
                // pcAddr = (pcAddr << 16) ^ fr.pc;
            }

            var id = locationIDs.get(pcAddr)
            if (id == null) {
                id = pcAddr

                val line: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    line,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.LINE_FUNCTION_ID,
                    getFunctionID(fn, fnAddr.toLong())
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    line,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.LINE_LINE,
                    fr.getLocation().line().toLong()
                )

                val loc: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    loc,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.LOCATION_ID,
                    id
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeLong(
                    loc,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.LOCATION_ADDRESS,
                    pcAddr
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                    loc,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.LOCATION_LINE,
                    line.toByteArray()
                )
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                    gz,
                    net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.PROFILE_LOCATION,
                    loc.toByteArray()
                )

                locationIDs.put(pcAddr, id)
            }
            return id
        }

        companion object {
            // Protocol encoding helpers; see https://developers.google.com/protocol-buffers/docs/encoding.
            // (Copied to avoid a dependency on the corresponding methods of protobuf.CodedOutputStream.)
            @Throws(IOException::class)
            private fun writeLong(out: java.io.OutputStream, fieldNumber: Int, x: Long) {
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeVarint(
                    out,
                    ((fieldNumber shl 3) or 0).toLong()
                ) // wire type 0 = varint
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeVarint(out, x)
            }

            @Throws(IOException::class)
            private fun writeString(out: java.io.OutputStream, fieldNumber: Int, x: String) {
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeByteArray(
                    out,
                    fieldNumber,
                    x.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            }

            @Throws(IOException::class)
            private fun writeByteArray(out: java.io.OutputStream, fieldNumber: Int, x: ByteArray) {
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeVarint(
                    out,
                    ((fieldNumber shl 3) or 2).toLong()
                ) // wire type 2 = length-delimited
                net.starlark.java.eval.CpuProfiler.PprofWriter.Companion.writeVarint(out, x.size.toLong())
                out.write(x)
            }

            @Throws(IOException::class)
            private fun writeVarint(out: java.io.OutputStream, value: Long) {
                var value = value
                while ((value and 0x7fL.inv()) != 0L) {
                    out.write(((value and 0x7fL) or 0x80L).toByte().toInt())
                    value = value ushr 7
                }
                out.write(value.toByte().toInt())
            }

            // Field numbers from pprof protocol.
            // See https://github.com/google/pprof/blob/master/proto/profile.proto
            private const val PROFILE_SAMPLE_TYPE = 1 // repeated ValueType
            private const val PROFILE_SAMPLE = 2 // repeated Sample
            private const val PROFILE_MAPPING = 3 // repeated Mapping
            private const val PROFILE_LOCATION = 4 // repeated Location
            private const val PROFILE_FUNCTION = 5 // repeated Function
            private const val PROFILE_STRING_TABLE = 6 // repeated string
            private const val PROFILE_TIME_NANOS = 9 // int64
            private const val PROFILE_DURATION_NANOS = 10 // int64
            private const val PROFILE_PERIOD_TYPE = 11 // ValueType
            private const val PROFILE_PERIOD = 12 // int64
            private const val VALUETYPE_TYPE = 1 // int64
            private const val VALUETYPE_UNIT = 2 // int64
            private const val SAMPLE_LOCATION_ID = 1 // repeated uint64
            private const val SAMPLE_VALUE = 2 // repeated int64
            private const val SAMPLE_LABEL = 3 // repeated Label
            private const val LABEL_KEY = 1 // int64
            private const val LABEL_STR = 2 // int64
            private const val LABEL_NUM = 3 // int64
            private const val LABEL_NUM_UNIT = 4 // int64
            private const val LOCATION_ID = 1 // uint64
            private const val LOCATION_MAPPING_ID = 2 // uint64
            private const val LOCATION_ADDRESS = 3 // uint64
            private const val LOCATION_LINE = 4 // repeated Line
            private const val LINE_FUNCTION_ID = 1 // uint64
            private const val LINE_LINE = 2 // int64
            private const val FUNCTION_ID = 1 // uint64
            private const val FUNCTION_NAME = 2 // int64
            private const val FUNCTION_SYSTEM_NAME = 3 // int64
            private const val FUNCTION_FILENAME = 4 // int64
            private const val FUNCTION_START_LINE = 5 // int64
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // Native profiler support, if available.
        @kotlin.concurrent.Volatile
        private var nativeSupport: net.starlark.java.eval.CpuProfilerNativeSupport? = null

        /** Installs native profiler support.  */
        fun setNativeSupport(nativeSupport: net.starlark.java.eval.CpuProfilerNativeSupport?) {
            net.starlark.java.eval.CpuProfiler.Companion.nativeSupport = nativeSupport
        }

        // The active profiler, if any.
        @kotlin.concurrent.Volatile
        private var instance: CpuProfiler? = null

        /** Returns the active profiler, or null if inactive.  */
        fun get(): CpuProfiler? {
            return net.starlark.java.eval.CpuProfiler.Companion.instance
        }

        // Maps OS thread ID to StarlarkThread.
        // The StarlarkThread is needed only for its cpuTicks field.
        private val threads: MutableMap<Int?, net.starlark.java.eval.StarlarkThread?> =
            ConcurrentHashMap<Int?, net.starlark.java.eval.StarlarkThread?>()

        /**
         * Associates the specified StarlarkThread with the current OS thread. Returns the StarlarkThread
         * previously associated with it, if any.
         */
        fun setStarlarkThread(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkThread? {
            if (thread == null) {
                return net.starlark.java.eval.CpuProfiler.Companion.threads.remove(net.starlark.java.eval.CpuProfiler.Companion.nativeSupport.getThreadId())
            } else {
                return net.starlark.java.eval.CpuProfiler.Companion.threads.put(
                    net.starlark.java.eval.CpuProfiler.Companion.nativeSupport.getThreadId(),
                    thread
                )
            }
        }

        /** Start the profiler.  */
        fun start(out: java.io.OutputStream, period: java.time.Duration): Boolean {
            if (net.starlark.java.eval.CpuProfiler.Companion.nativeSupport == null) {
                net.starlark.java.eval.CpuProfiler.Companion.logger.atWarning()
                    .log("--starlark_cpu_profile is unsupported on this platform")
                return false
            }
            check(net.starlark.java.eval.CpuProfiler.Companion.instance == null) { "profiler started twice without intervening stop" }

            net.starlark.java.eval.CpuProfiler.Companion.startRouter()
            check(
                net.starlark.java.eval.CpuProfiler.Companion.nativeSupport.startTimer(
                    TimeUnit.MICROSECONDS.convert(
                        period
                    )
                )
            ) { "profile signal handler already in use" }

            net.starlark.java.eval.CpuProfiler.Companion.instance = net.starlark.java.eval.CpuProfiler(out, period)
            return true
        }

        /** Stop the profiler and wait for the log to be written.  */
        @Throws(IOException::class)
        fun stop() {
            checkNotNull(net.starlark.java.eval.CpuProfiler.Companion.instance) { "stop without start" }

            val profiler: CpuProfiler? = net.starlark.java.eval.CpuProfiler.Companion.instance
            net.starlark.java.eval.CpuProfiler.Companion.instance = null

            net.starlark.java.eval.CpuProfiler.Companion.nativeSupport.stopTimer()

            // Finish writing the file and fail if there were any I/O errors.
            profiler!!.pprof.writeEnd()
        }

        // ---- signal router ----
        private var pipe: FileInputStream? = null

        // Starts the routing thread if not already started (idempotent).
        // On return, it is safe to install the signal handler.
        @kotlin.jvm.Synchronized
        private fun startRouter() {
            if (net.starlark.java.eval.CpuProfiler.Companion.pipe == null) {
                net.starlark.java.eval.CpuProfiler.Companion.pipe =
                    FileInputStream(net.starlark.java.eval.CpuProfiler.Companion.nativeSupport.createPipe())
                val router: java.lang.Thread = java.lang.Thread(
                    java.lang.Runnable { net.starlark.java.eval.CpuProfiler.Companion.router() },
                    "SIGPROF router"
                )
                router.setDaemon(true)
                router.start()
            }
        }

        // The Router thread routes SIGPROF events (from the pipe)
        // to the relevant StarlarkThread. Once started, it runs forever.
        //
        // TODO(adonovan): opt: a more efficient implementation of routing would be
        // to use, instead of a pipe from the signal handler to the routing thread,
        // a mapping, maintained in C++, from OS thread ID to cpuTicks pointer.
        // The {add,remove}Thread operations would update this mapping,
        // and the signal handler would read it. The mapping would have to
        // be a lock-free hash table so that it can be safely read in an
        // async signal handler. The pointer would point to the sole element
        // of direct memory buffer belonging to the StarlarkThread, allocated
        // by JNI NewDirectByteBuffer.
        // In this way, the signal handler could update the StarlarkThread directly,
        // saving 100 write+read calls per second per core.
        //
        private fun router() {
            val buf = ByteArray(4)
            while (true) {
                try {
                    val n: Int = net.starlark.java.eval.CpuProfiler.Companion.pipe.read(buf)
                    check(n >= 0) { "pipe closed" }
                    check(n == 4) { "short read" }
                } catch (ex: IOException) {
                    throw java.lang.IllegalStateException("unexpected I/O error", ex)
                }

                val tid: Int = net.starlark.java.eval.CpuProfiler.Companion.int32be(buf)

                // Record a CPU tick against tid.
                //
                // It's not safe to grab the thread's stack here because the thread
                // may be changing it, so we increment the thread's counter.
                // When the thread later observes the counter is non-zero,
                // it gives us the stack by calling addEvent.
                val thread: net.starlark.java.eval.StarlarkThread? =
                    net.starlark.java.eval.CpuProfiler.Companion.threads.get(tid)
                if (thread != null) {
                    thread.cpuTicks.getAndIncrement()
                }
            }
        }

        // Decodes a signed 32-bit big-endian integer from b[0:4].
        private fun int32be(b: ByteArray): Int {
            return b[0].toInt() shl 24 or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        }
    }
}
