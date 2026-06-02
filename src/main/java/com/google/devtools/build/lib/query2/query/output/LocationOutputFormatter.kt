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

import com.google.common.collect.ImmutableList
import com.google.common.hash.HashFunction
import com.google.devtools.build.lib.packages.LabelPrinter
import com.google.devtools.build.lib.query2.engine.QueryException
import java.io.OutputStream

/**
 * An output formatter that prints the labels of the targets, preceded by
 * their locations and kinds, in topological order.  For output files, the
 * location of the generating rule is given; for input files, the location of
 * line 1 is given.
 */
internal class LocationOutputFormatter : AbstractUnorderedFormatter() {
    private var relativeLocations = false

    override fun getName(): String {
        return "location"
    }

    override fun setOptions(
        options: CommonQueryOptions, aspectResolver: AspectResolver?, hashFunction: HashFunction?
    ) {
        super.setOptions(options, aspectResolver, hashFunction)
        this.relativeLocations = options.getRelativeLocations()
    }

    @Throws(QueryException::class)
    override fun verifyCompatible(env: QueryEnvironment<*>?, expr: QueryExpression) {
        if (env !is AbstractBlazeQueryEnvironment<*>) {
            return
        }

        val noteBuildFilesAndLoadLilesVisitor: ContainsFunctionQueryExpressionVisitor =
            ContainsFunctionQueryExpressionVisitor(ImmutableList.of<String?>("loadfiles", "buildfiles"))

        if (expr.accept<Boolean?>(noteBuildFilesAndLoadLilesVisitor)) {
            throw QueryException(
                "Query expressions involving 'buildfiles' or 'loadfiles' cannot be used with "
                        + "--output=location",
                Query.Code.BUILDFILES_AND_LOADFILES_CANNOT_USE_OUTPUT_LOCATION_ERROR
            )
        }
    }

    override fun createPostFactoStreamCallback(
        out: OutputStream?, options: QueryOptions, labelPrinter: LabelPrinter
    ): OutputFormatterCallback<Target?> {
        return object : TextOutputFormatterCallback<Target?>(out) {
            @Throws(IOException::class)
            override fun processOutput(partialResult: Iterable<Target>) {
                val lineTerm = options.getLineTerminator()
                for (target in partialResult) {
                    writer
                        .append(FormatUtils.getLocation(target, relativeLocations))
                        .append(": ")
                        .append(AbstractUnorderedFormatter.Companion.getKind(options, target))
                        .append(" ")
                        .append(labelPrinter.toString(target.getLabel()))
                        .append(lineTerm)
                }
            }
        }
    }

    override fun createStreamCallback(
        out: OutputStream?, options: QueryOptions, env: QueryEnvironment<*>
    ): ThreadSafeOutputFormatterCallback<Target?> {
        return SynchronizedDelegatingOutputFormatterCallback<Target?>(
            createPostFactoStreamCallback(out, options, env.getLabelPrinter())
        )
    }
}
