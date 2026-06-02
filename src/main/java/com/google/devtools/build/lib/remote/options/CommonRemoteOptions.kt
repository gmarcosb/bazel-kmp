// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.options

import com.google.devtools.common.options.*
import java.time.Duration
import java.util.regex.Pattern

/** Options for remote execution and distributed caching that shared between Bazel and Blaze.  */
@OptionsClass
abstract class CommonRemoteOptions : OptionsBase() {
    @get:Option(
        name = "remote_download_regex",
        oldName = "experimental_remote_download_regex",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        converter = Converters.RegexPatternConverter::class,
        help = ("Force remote build outputs whose path matches this pattern to be downloaded,"
                + " irrespective of --remote_download_outputs. Multiple patterns may be specified by"
                + " repeating this flag.")
    )
    abstract val remoteDownloadRegex: MutableList<RegexPatternOption?>?

    @get:Option(
        name = "experimental_remote_cache_ttl",
        defaultValue = "3h",
        documentationCategory = OptionDocumentationCategory.REMOTE,
        effectTags = [OptionEffectTag.EXECUTION],
        converter = RemoteDurationConverter::class,
        help = ("The guaranteed minimal TTL of blobs in the remote cache after their digests are recently"
                + " referenced e.g. by an ActionResult or FindMissingBlobs. Bazel does several"
                + " optimizations based on the blobs' TTL e.g. doesn't repeatedly call"
                + " GetActionResult in an incremental build. The value should be set slightly less"
                + " than the real TTL since there is a gap between when the server returns the"
                + " digests and when Bazel receives them.")
    )
    abstract val remoteCacheTtl: Duration?

    /** Returns the specified duration. Assumes seconds if unitless.  */
    class RemoteDurationConverter : Converter.Contextless<Duration?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: String?): Duration? {
            var input = input
            if (UNITLESS_REGEX.matcher(input).matches()) {
                input += "s"
            }
            return Converters.DurationConverter().convert(input,  /* conversionContext= */null)
        }

        override fun getTypeDescription(): String {
            return "An immutable length of time."
        }

        companion object {
            private val UNITLESS_REGEX: Pattern = Pattern.compile("^[0-9]+$")
        }
    }
}
