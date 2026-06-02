// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.events.EventHandler
import java.io.OutputStream

/** Static utility methods for outputting a query.  */
object QueryOutputUtils {
    fun lexicographicallySortOutput(
        queryOptions: QueryOptions, formatter: OutputFormatter?
    ): Boolean {
        return queryOptions.getOrderOutput() == OrderOutput.AUTO
                && formatter is StreamedFormatter
    }

    fun shouldStreamUnorderedOutput(
        queryOptions: QueryOptions, formatter: OutputFormatter?
    ): Boolean {
        return queryOptions.getOrderOutput() == OrderOutput.NO
                && formatter is StreamedFormatter
    }

    fun shouldStreamResults(queryOptions: QueryOptions, formatter: OutputFormatter?): Boolean {
        return shouldStreamUnorderedOutput(queryOptions, formatter)
                || lexicographicallySortOutput(queryOptions, formatter)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun output(
        queryOptions: QueryOptions,
        result: QueryEvalResult?,
        targetsResult: MutableSet<Target?>,
        formatter: OutputFormatter,
        outputStream: OutputStream?,
        aspectResolver: AspectResolver?,
        eventHandler: EventHandler?,
        hashFunction: HashFunction?,
        labelPrinter: LabelPrinter?
    ) {
        /*
     * This is not really streaming, but we are using the streaming interface for writing into the
     * output everything in one batch. This happens when the QueryEnvironment does not
     * support streaming but we don't care about ordered results.
     */
        if (shouldStreamResults(queryOptions, formatter)) {
            val streamedFormatter = formatter as StreamedFormatter
            streamedFormatter.setOptions(queryOptions, aspectResolver, hashFunction)
            streamedFormatter.setEventHandler(eventHandler)
            OutputFormatterCallback.Companion.processAllTargets<Target?>(
                streamedFormatter.createPostFactoStreamCallback(outputStream, queryOptions, labelPrinter),
                targetsResult
            )
        } else {
            val digraphQueryEvalResult: DigraphQueryEvalResult<Target?> =
                result as DigraphQueryEvalResult<Target?>
            val subgraph: Digraph<Target?>?

            Profiler.instance().profile("digraph.extractSubgraph").use { closeable ->
                subgraph = digraphQueryEvalResult.getGraph().extractSubgraph(targetsResult)
            }
            Profiler.instance().profile("formatter.output").use { closeable ->
                formatter.output(
                    queryOptions,
                    subgraph,
                    outputStream,
                    aspectResolver,
                    eventHandler,
                    hashFunction,
                    labelPrinter
                )
            }
        }
    }
}
