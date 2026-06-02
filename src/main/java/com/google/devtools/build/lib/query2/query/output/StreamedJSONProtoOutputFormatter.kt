// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.LabelPrinter
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * An output formatter that prints a list of targets according to ndjson spec to the output print
 * stream.
 */
class StreamedJSONProtoOutputFormatter : ProtoOutputFormatter() {
    override fun getName(): String {
        return "streamed_jsonproto"
    }

    private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer()

    override fun createPostFactoStreamCallback(
        out: OutputStream, options: QueryOptions?, labelPrinter: LabelPrinter?
    ): OutputFormatterCallback<Target?> {
        return object : OutputFormatterCallback<Target?>() {
            @Throws(IOException::class, InterruptedException::class)
            override fun processOutput(partialResult: Iterable<Target?>) {
                for (target in partialResult) {
                    out.write(
                        jsonPrinter
                            .omittingInsignificantWhitespace()
                            .print(toTargetProtoBuffer(target, labelPrinter))
                            .getBytes(StandardCharsets.UTF_8)
                    )
                    out.write('\n'.code)
                }
            }
        }
    }
}
