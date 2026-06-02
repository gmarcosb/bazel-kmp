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

import com.google.devtools.build.lib.cmdline.Label
import java.io.OutputStream

/**
 * An output formatter that prints the labels of the resulting target set in
 * topological order, optionally with the target's kind.
 */
internal class LabelOutputFormatter(private val showKind: Boolean) : AbstractUnorderedFormatter() {
    override fun getName(): String {
        return if (showKind) "label_kind" else "label"
    }

    override fun createPostFactoStreamCallback(
        out: OutputStream?, options: QueryOptions, labelPrinter: LabelPrinter
    ): OutputFormatterCallback<Target?> {
        return object : TextOutputFormatterCallback<Target?>(out) {
            @Throws(IOException::class)
            override fun processOutput(partialResult: Iterable<Target>) {
                val lineTerm = options.getLineTerminator()
                for (target in partialResult) {
                    if (showKind) {
                        writer.append(AbstractUnorderedFormatter.Companion.getKind(options, target))
                        writer.append(' ')
                    }
                    val label: Label? = target.getLabel()
                    writer.append(labelPrinter.toString(label)).append(lineTerm)
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
