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

import com.google.common.base.Predicates
import com.google.common.collect.ImmutableList
import com.google.common.collect.Streams
import java.util.stream.Collectors

/** Encapsulates available [OutputFormatter]s and selection logic.  */
object OutputFormatters {
    @kotlin.jvm.JvmStatic
    val defaultFormatters: ImmutableList<OutputFormatter?>
        /** Returns all available [OutputFormatter]s.  */
        get() = ImmutableList.of<OutputFormatter?>(
            LabelOutputFormatter(false),
            LabelOutputFormatter(true),
            BuildOutputFormatter(),
            MinrankOutputFormatter(),
            MaxrankOutputFormatter(),
            PackageOutputFormatter(),
            LocationOutputFormatter(),
            GraphOutputFormatter(),
            XmlOutputFormatter(),
            ProtoOutputFormatter(),
            StreamedJSONProtoOutputFormatter(),
            StreamedProtoOutputFormatter()
        )

    /** Returns the names of all [OutputFormatter]s in the input.  */
    fun formatterNames(formatters: Iterable<OutputFormatter?>): String? {
        return Streams.stream<OutputFormatter?>(formatters).map<String?> { obj: OutputFormatter? -> obj!!.getName() }
            .collect(Collectors.joining(", "))
    }

    /** Returns the name of all streaming [OutputFormatter]s in the input.  */
    fun streamingFormatterNames(formatters: Iterable<OutputFormatter?>): String? {
        return Streams.stream<OutputFormatter?>(formatters)
            .filter(Predicates.instanceOf<OutputFormatter?>(StreamedFormatter::class.java))
            .map<String?> { obj: OutputFormatter? -> obj!!.getName() }
            .collect(Collectors.joining(", "))
    }

    /** Returns the [OutputFormatter] for the specified type.  */
    fun getFormatter(formatters: Iterable<OutputFormatter>, type: String?): OutputFormatter? {
        for (formatter in formatters) {
            if (formatter.getName() == type) {
                return formatter
            }
        }

        return null
    }
}
