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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.events.EventHandler
import java.io.OutputStream

/**
 * Unordered streamed output formatter (wrt. dependency ordering).
 * 
 * 
 * Formatters that support streamed output may be used when only the set of query results is
 * requested but their ordering is irrelevant.
 * 
 * 
 * The benefit of using a streamed formatter is that we can save the potentially expensive
 * subgraph extraction step before presenting the query results and that depending on the query
 * environment used, it can be more memory performant, as it does not aggregate all the data
 * before writing in the output.
 */
interface StreamedFormatter {
    /** Specifies options to be used by subsequent calls to [.createStreamCallback].  */
    fun setOptions(
        options: CommonQueryOptions?, aspectResolver: AspectResolver?, hashFunction: HashFunction?
    )

    /** Sets an optional handler for reporting status output / errors.  */
    fun setEventHandler(eventHandler: EventHandler?)

    /**
     * Returns a [ThreadSafeOutputFormatterCallback] whose
     * [OutputFormatterCallback.process] outputs formatted [Target]s to the given
     * `out`.
     * 
     * 
     * Takes any options specified via the most recent call to [.setOptions] into
     * consideration.
     * 
     * 
     * Intended to be use for streaming out during evaluation of a query.
     */
    fun createStreamCallback(
        out: OutputStream?, options: QueryOptions?, env: QueryEnvironment<*>?
    ): ThreadSafeOutputFormatterCallback<Target?>?

    /**
     * Same as [.createStreamCallback], but intended to be used for outputting the
     * already-computed result of a query.
     */
    fun createPostFactoStreamCallback(
        out: OutputStream?, options: QueryOptions?, labelPrinter: LabelPrinter?
    ): OutputFormatterCallback<Target?>?
}