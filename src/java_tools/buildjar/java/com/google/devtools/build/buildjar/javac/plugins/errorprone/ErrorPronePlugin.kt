// Copyright 2011 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.buildjar.javac.plugins.errorprone

import com.google.common.base.Stopwatch
import com.google.common.collect.ImmutableList
import com.google.devtools.build.buildjar.InvalidCommandLineException
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.sun.tools.javac.comp.Env
import com.sun.tools.javac.main.JavaCompiler
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import java.time.Duration
import java.util.Map

/**
 * A plugin that performs Error Prone analysis. Error Prone is a static analysis framework that we
 * use to perform some simple static checks on Java code.
 */
class ErrorPronePlugin @kotlin.jvm.JvmOverloads constructor(scannerSupplier: ScannerSupplier? = BuiltInCheckerSuppliers.errorChecks()) :
    BlazeJavaCompilerPlugin() {
    private val scannerSupplier: ScannerSupplier?

    private var errorProneAnalyzer: ErrorProneAnalyzer? = null
    private var epOptions: ErrorProneOptions? = null
    private var timings: ErrorProneTimings? = null
    private var deferredCompletionFailureHandler: DeferredCompletionFailureHandler? = null
    private val elapsed: Stopwatch = Stopwatch.createUnstarted()

    /**
     * Constructs an [ErrorPronePlugin] with the set of checks that are enabled in `scannerSupplier`.
     */
    /**
     * Constructs an [ErrorPronePlugin] instance with the set of checks that are enabled as
     * errors in open-source Error Prone.
     */
    init {
        this.scannerSupplier = scannerSupplier
    }

    @Throws(InvalidCommandLineException::class)
    public override fun processArgs(
        standardJavacopts: ImmutableList<String?>?, blazeJavacopts: ImmutableList<String?>
    ) {
        val epArgs = ImmutableList.builder<String?>().addAll(blazeJavacopts)
        // allow javacopts that reference unknown error-prone checks
        epArgs.add("-XepIgnoreUnknownCheckNames")
        processEpOptions(epArgs.build())
    }

    @Throws(InvalidCommandLineException::class)
    private fun processEpOptions(args: MutableList<String?>) {
        try {
            epOptions = ErrorProneOptions.processArgs(args)
        } catch (e: InvalidCommandLineOptionException) {
            throw InvalidCommandLineException(e.message)
        }
    }

    public override fun init(
        context: Context,
        log: Log?,
        compiler: JavaCompiler?,
        statisticsBuilder: BlazeJavacStatistics.Builder?
    ) {
        super.init(context, log, compiler, statisticsBuilder)

        setupMessageBundle(context)

        if (epOptions == null) {
            epOptions = ErrorProneOptions.empty()
        }
        errorProneAnalyzer =
            ErrorProneAnalyzer.createByScanningForPlugins(scannerSupplier, epOptions, context)
        timings = ErrorProneTimings.instance(context)
        deferredCompletionFailureHandler = DeferredCompletionFailureHandler.instance(context)
    }

    /** Run Error Prone analysis after performing dataflow checks.  */
    public override fun postFlow(env: Env<AttrContext?>) {
        val previousDeferredCompletionFailureHandler: DeferredCompletionFailureHandler.Handler? =
            deferredCompletionFailureHandler.setHandler(
                deferredCompletionFailureHandler.userCodeHandler
            )
        elapsed.start()
        try {
            errorProneAnalyzer.finished(TaskEvent(TaskEvent.Kind.ANALYZE, env.toplevel, env.enclClass.sym))
        } catch (e: ErrorProneError) {
            e.logFatalError(log, context)
            // let the exception propagate to javac's main, where it will cause the compilation to
            // terminate with Result.ABNORMAL
            throw e
        } finally {
            elapsed.stop()
            deferredCompletionFailureHandler.setHandler(previousDeferredCompletionFailureHandler)
        }
    }

    public override fun finish() {
        statisticsBuilder.totalErrorProneTime(elapsed.elapsed())
        statisticsBuilder.errorProneInitializationTime(timings.initializationTime())
        timings.timings().entries.stream()
            .sorted(Map.Entry.comparingByValue<String?, Duration?>().reversed())
            .limit(10) // best-effort to stay under the action metric size limit
            .forEachOrdered { e: MutableMap.MutableEntry<String?, Duration?>? ->
                statisticsBuilder.addBugpatternTiming(
                    e!!.key,
                    e.value
                )
            }
    }

    public override fun runOnFlowErrors(): Boolean {
        return true
    }

    companion object {
        /** Registers our message bundle.  */
        fun setupMessageBundle(context: Context) {
            BaseErrorProneJavaCompiler.setupMessageBundle(context)
        }
    }
}
