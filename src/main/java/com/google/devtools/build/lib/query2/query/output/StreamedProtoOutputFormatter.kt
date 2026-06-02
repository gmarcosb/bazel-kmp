// Copyright 2019 The Bazel Authors. All rights reserved.
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

/**
 * An output formatter that outputs a protocol buffer representation of a query result and outputs
 * the proto bytes to the output print stream. By taking the bytes and calling `mergeFrom()`
 * on a `Build.QueryResult` object the full result can be reconstructed.
 */
class StreamedProtoOutputFormatter : ProtoOutputFormatter() {
    override fun getName(): String {
        return "streamed_proto"
    }

    override fun createPostFactoStreamCallback(
        out: OutputStream?, options: QueryOptions?, labelPrinter: LabelPrinter?
    ): OutputFormatterCallback<Target?> {
        return object : OutputFormatterCallback<Target?>() {
            @Throws(IOException::class, InterruptedException::class)
            override fun processOutput(partialResult: Iterable<Target?>) {
                for (target in partialResult) {
                    toTargetProtoBuffer(target, labelPrinter).writeDelimitedTo(out)
                }
            }
        }
    }
}
