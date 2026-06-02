// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action

/** Outputs various messages of analysis_v2.proto.  */
interface AqueryOutputHandler : AutoCloseable {
    /** Defines the types of proto output this class can handle.  */
    enum class OutputType(formatName: String) {
        BINARY("proto"),
        DELIMITED_BINARY("streamed_proto"),
        TEXT("textproto"),
        JSON("jsonproto");

        private val formatName: String?

        init {
            this.formatName = formatName
        }

        fun formatName(): String? {
            return formatName
        }

        companion object {
            @Throws(InvalidAqueryOutputFormatException::class)
            fun fromString(string: String): OutputType {
                when (string) {
                    "proto" -> return OutputType.BINARY
                    "streamed_proto" -> return OutputType.DELIMITED_BINARY
                    "textproto" -> return OutputType.TEXT
                    "jsonproto" -> return OutputType.JSON
                    else -> {}
                }
                throw InvalidAqueryOutputFormatException("Invalid aquery output format: " + string)
            }
        }
    }

    @Throws(IOException::class)
    fun outputArtifact(message: Artifact?)

    @Throws(IOException::class)
    fun outputAction(message: Action?)

    @Throws(IOException::class)
    fun outputTarget(message: Target?)

    @Throws(IOException::class)
    fun outputDepSetOfFiles(message: DepSetOfFiles?)

    @Throws(IOException::class)
    fun outputConfiguration(message: Configuration?)

    @Throws(IOException::class)
    fun outputAspectDescriptor(message: AspectDescriptor?)

    @Throws(IOException::class)
    fun outputRuleClass(message: RuleClass?)

    @Throws(IOException::class)
    fun outputPathFragment(message: PathFragment?)

    /** Called at the end of the query process.  */
    @Throws(IOException::class)
    override fun close()
}
