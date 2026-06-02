// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.actions.CommandLineExpansionException

/** Default output callback for aquery, prints proto output.  */
class ActionGraphProtoOutputFormatterCallback internal constructor(
    eventHandler: ExtendedEventHandler?,
    options: AqueryOptions,
    out: java.io.OutputStream,
    accessor: TargetAccessor<ConfiguredTargetValue?>?,
    outputType: com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler.OutputType,
    actionFilters: AqueryActionFilter?
) : AqueryThreadsafeCallback(eventHandler, options, out, accessor) {
    private val outputType: com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler.OutputType
    private val actionGraphDump: ActionGraphDump
    private val actionFilters: AqueryActionFilter?
    private val aqueryOutputHandler: AqueryOutputHandler

    init {
        this.outputType = outputType
        this.actionFilters = actionFilters
        this.aqueryOutputHandler = constructAqueryOutputHandler(outputType, out, printStream)
        this.actionGraphDump =
            ActionGraphDump(
                options.getIncludeCommandline(),
                options.getIncludeArtifacts(),
                options.getIncludePrunedInputs(),
                this.actionFilters,
                options.getIncludeParamFiles(),
                options.getIncludeFileWriteContents(),
                aqueryOutputHandler,
                eventHandler
            )
    }

    val name: String?
        get() = outputType.formatName()

    @Throws(IOException::class)
    override fun close(failFast: Boolean) {
        if (!failFast) {
            Profiler.instance().profile("aqueryOutputHandler.close").use { c ->
                aqueryOutputHandler.close()
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<ConfiguredTargetValue?>) {
        if (aqueryOutputHandler is AqueryConsumingOutputHandler) {
            processOutputInParallel(partialResult)
            return
        }

        try {
            Profiler.instance().profile("process partial result").use { c ->
                // Enabling includeParamFiles should enable includeCommandline by default.
                options.setIncludeCommandline(
                    options.getIncludeCommandline() || options.getIncludeParamFiles()
                )
                for (configuredTargetValue in partialResult) {
                    processSingleEntry(configuredTargetValue)
                }
            }
        } catch (e: CommandLineExpansionException) {
            throw IOException(e.getMessage())
        } catch (e: TemplateExpansionException) {
            throw IOException(e.getMessage())
        }
    }

    @Throws(
        CommandLineExpansionException::class,
        java.lang.InterruptedException::class,
        IOException::class,
        TemplateExpansionException::class
    )
    private fun processSingleEntry(configuredTargetValue: ConfiguredTargetValue?) {
        if (configuredTargetValue !is RuleConfiguredTargetValue) {
            // We have to include non-rule values in the graph to visit their dependencies, but they
            // don't have any actions to print out.
            return
        }
        actionGraphDump.dumpConfiguredTarget(configuredTargetValue as RuleConfiguredTargetValue?)
        if (options.getUseAspects()) {
            for (aspectValue in accessor.getAspectValues(configuredTargetValue)) {
                actionGraphDump.dumpAspect(aspectValue, configuredTargetValue)
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun processOutputInParallel(partialResult: Iterable<ConfiguredTargetValue?>) {
        val aqueryConsumingOutputHandler: AqueryConsumingOutputHandler =
            aqueryOutputHandler as AqueryConsumingOutputHandler
        Profiler.instance().profile("process partial result").use { c ->
            // Enabling includeParamFiles should enable includeCommandline by default.
            options.setIncludeCommandline(
                options.getIncludeCommandline() || options.getIncludeParamFiles()
            )
            val executor: ForkJoinPool =
                NamedForkJoinPool.newNamedPool("aquery", java.lang.Runtime.getRuntime().availableProcessors())
            try {
                val consumerFuture: java.util.concurrent.Future<java.lang.Void?> =
                    executor.submit<java.lang.Void?>(aqueryConsumingOutputHandler.startConsumer())
                val futures: MutableList<java.util.concurrent.Future<java.lang.Void?>> =
                    executor.invokeAll<java.lang.Void?>(toTasks(partialResult))
                for (future in futures) {
                    future.get()
                }
                aqueryConsumingOutputHandler.stopConsumer( /* discardRemainingTasks= */false)
                // Get any possible exception from the consumer.
                consumerFuture.get()
            } catch (e: ExecutionException) {
                aqueryConsumingOutputHandler.stopConsumer( /* discardRemainingTasks= */true)
                val cause: Throwable = com.google.common.base.Throwables.getRootCause(e)
                if (cause is CommandLineExpansionException
                    || cause is TemplateExpansionException
                ) {
                    // This is kinda weird, but keeping it in line with the status quo for now.
                    // TODO(b/266179316): Clean this up.
                    throw IOException(cause.message)
                }
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    cause,
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(cause)
                throw java.lang.IllegalStateException("Unexpected exception type: ", e)
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun toTasks(values: Iterable<ConfiguredTargetValue?>): com.google.common.collect.ImmutableList<AqueryOutputTask?> {
        val tasks: com.google.common.collect.ImmutableList.Builder<AqueryOutputTask?> =
            com.google.common.collect.ImmutableList.builder<AqueryOutputTask?>()
        for (value in values) {
            tasks.add(AqueryOutputTask(value))
        }
        return tasks.build()
    }

    private inner class AqueryOutputTask(configuredTargetValue: ConfiguredTargetValue?) :
        java.util.concurrent.Callable<java.lang.Void?> {
        private val configuredTargetValue: ConfiguredTargetValue?

        init {
            this.configuredTargetValue = configuredTargetValue
        }

        @Throws(
            CommandLineExpansionException::class,
            TemplateExpansionException::class,
            IOException::class,
            java.lang.InterruptedException::class
        )
        override fun call(): java.lang.Void? {
            processSingleEntry(configuredTargetValue)
            return null
        }
    }

    companion object {
        // Arbitrarily chosen. Large enough for good performance, small enough not to cause OOMs.
        private val BLOCKING_QUEUE_SIZE: Int = java.lang.Runtime.getRuntime().availableProcessors() * 2

        /**
         * Pseudo-arbitrarily chosen buffer size for output. Chosen to be large enough to fit a handful of
         * messages without needing to flush to the underlying output, which may not be buffered.
         */
        private const val OUTPUT_BUFFER_SIZE = 16384

        fun constructAqueryOutputHandler(
            outputType: com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler.OutputType,
            out: java.io.OutputStream,
            printStream: PrintStream?
        ): AqueryOutputHandler {
            return when (outputType) {
                AqueryOutputHandler.OutputType.BINARY, AqueryOutputHandler.OutputType.DELIMITED_BINARY, AqueryOutputHandler.OutputType.TEXT -> StreamedConsumingOutputHandler(
                    outputType,
                    out,
                    CodedOutputStream.newInstance(out, OUTPUT_BUFFER_SIZE),
                    printStream,
                    LinkedBlockingQueue<PrintTask?>(BLOCKING_QUEUE_SIZE)
                )

                AqueryOutputHandler.OutputType.JSON -> MonolithicOutputHandler(printStream)
            }
        }
    }
}
