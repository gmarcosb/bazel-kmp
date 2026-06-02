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

import com.google.common.collect.Sets
import com.google.devtools.build.lib.packages.LabelPrinter
import java.io.OutputStream

/**
 * An output formatter that prints the names of the packages of the target set, in lexicographical
 * order without duplicates.
 */
internal class PackageOutputFormatter : AbstractUnorderedFormatter() {
    override fun getName(): String {
        return "package"
    }

    override fun createPostFactoStreamCallback(
        out: OutputStream?, options: QueryOptions, labelPrinter: LabelPrinter
    ): OutputFormatterCallback<Target?> {
        return object : TextOutputFormatterCallback<Target?>(out) {
            private val packageNames: MutableSet<String?> = Sets.newTreeSet<String?>()

            override fun processOutput(partialResult: Iterable<Target>) {
                for (target in partialResult) {
                    var packageLabel = labelPrinter.toString(target.getLabel().getPackageIdentifier())
                    // For backwards compatibility, emit main repo packages as "a/b" rather than "//a/b".
                    if (packageLabel.startsWith("//")) {
                        packageLabel = packageLabel.substring(2)
                    }
                    packageNames.add(packageLabel)
                }
            }

            @Throws(IOException::class)
            override fun close(failFast: Boolean) {
                if (!failFast) {
                    val lineTerm = options.getLineTerminator()
                    for (packageName in packageNames) {
                        writer.append(packageName).append(lineTerm)
                    }
                }
                super.close(failFast)
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