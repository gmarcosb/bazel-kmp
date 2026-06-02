// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.devtools.build.lib.packages.Attribute
import com.google.devtools.build.lib.query2.proto.proto2api.Build
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkSemantics
import java.io.OutputStream

/** Outputs repository definitions for `mod show_repo`.  */
class RepoOutputFormatter(printer: PrintWriter, outputStream: OutputStream?, outputFormat: ModOptions.OutputFormat) {
    private val printer: PrintWriter
    private val outputStream: OutputStream?
    private val outputFormat: ModOptions.OutputFormat

    init {
        this.printer = printer
        this.outputStream = outputStream
        this.outputFormat = outputFormat
    }

    fun print(key: String, repoDefinition: RepoDefinitionValue?) {
        when (outputFormat) {
            ModOptions.OutputFormat.TEXT -> printStarlark(key, repoDefinition)
            ModOptions.OutputFormat.STREAMED_JSONPROTO, ModOptions.OutputFormat.STREAMED_PROTO -> {
                // In proto output formats, we only print repo definitions, not overrides.
                if (repoDefinition is Found) {
                    if (outputFormat == ModOptions.OutputFormat.STREAMED_JSONPROTO) {
                        printProtoJson(key, repoDefinition.repoDefinition)
                    } else {
                        printStreamedProto(key, repoDefinition.repoDefinition)
                    }
                }
            }

            else -> throw IllegalArgumentException("Unknown output format: " + outputFormat)
        }
    }

    private fun printStarlark(key: String?, repoDefinition: RepoDefinitionValue?) {
        if (repoDefinition is Found) {
            printer.printf("## %s:\n", key)
            printStarlark(repoDefinition.repoDefinition)
        }
        if (repoDefinition is RepoDefinitionValue.RepoOverride) {
            printer.printf(
                "## %s:\nBuiltin or overridden repo located at: %s\n\n",
                key, repoDefinition.repoPath
            )
        }
    }

    private fun printStarlark(repoDefinition: RepoDefinition) {
        val repoRule: RepoRule = repoDefinition.repoRule
        printer
            .append("load(\"")
            .append(repoRule.id.bzlFileLabel.getUnambiguousCanonicalForm())
            .append("\", \"")
            .append(repoRule.id.ruleName)
            .append("\")\n")
        printer.append(repoRule.id.ruleName).append("(\n")
        printer.append("  name = \"").append(repoDefinition.name).append("\",\n")
        if (repoDefinition.originalName != null) {
            printer.append("  _original_name = \"").append(repoDefinition.originalName).append("\",\n")
        }
        for (attr in repoDefinition.attrValues.attributes().entrySet()) {
            printer
                .append("  ")
                .append(attr.getKey())
                .append(" = ")
                .append(Starlark.repr(attr.getValue(), StarlarkSemantics.DEFAULT))
                .append(",\n")
        }
        printer.append(")\n")
        // TODO: record and print the call stack for the repo definition itself?
        printer.append("\n")
    }

    private fun printStreamedProto(key: String, repoDefinition: RepoDefinition) {
        val serialized: Build.Repository = serializeRepoDefinitionAsProto(key, repoDefinition)
        try {
            serialized.writeDelimitedTo(outputStream)
        } catch (e: IOException) {
            // Ignore IOException like PrintWriter.
        }
    }

    private fun printProtoJson(key: String, repoDefinition: RepoDefinition) {
        val serialized: Build.Repository = serializeRepoDefinitionAsProto(key, repoDefinition)
        try {
            printer.println(jsonPrinter.print(serialized))
        } catch (e: InvalidProtocolBufferException) {
            throw IllegalArgumentException(e)
        }
    }

    private fun serializeRepoDefinitionAsProto(
        key: String, repoDefinition: RepoDefinition
    ): Build.Repository {
        val repoRule: RepoRule = repoDefinition.repoRule

        val pbBuilder: Build.Repository.Builder = Build.Repository.newBuilder()
        pbBuilder.setCanonicalName(StringEncoding.internalToUnicode(repoDefinition.name))
        pbBuilder.setRepoRuleName(StringEncoding.internalToUnicode(repoRule.id.ruleName))
        pbBuilder.setRepoRuleBzlLabel(
            StringEncoding.internalToUnicode(repoRule.id.bzlFileLabel.getUnambiguousCanonicalForm())
        )

        // TODO: record and print the call stack for the repo definition itself?
        if (key.startsWith("@")) {
            if (!key.startsWith("@@")) {
                pbBuilder.setApparentName(StringEncoding.internalToUnicode(key))
            }
        } else {
            pbBuilder.setModuleKey(StringEncoding.internalToUnicode(key))
        }
        if (repoDefinition.originalName != null) {
            pbBuilder.setOriginalName(StringEncoding.internalToUnicode(repoDefinition.originalName))
        }

        for (attr in repoRule.attributeIndices.entrySet()) {
            val attrName: String? = attr.getKey()
            val attrDefinition: Attribute = repoRule.attributes.get(attr.getValue())

            val explicitlySpecified: Boolean = repoDefinition.attrValues.attributes().containsKey(attrName)
            var attrValue: Any? = repoDefinition.attrValues.attributes().get(attrName)
            if (attrValue == null) {
                attrValue = attrDefinition.defaultValueUnchecked
            }
            val serializedAttribute: Build.Attribute? =
                AttributeFormatter.getAttributeProto(
                    attrDefinition,
                    attrValue,
                    explicitlySpecified,  /* encodeBooleanAndTriStateAsIntegerAndString= */
                    true,  /* sourceAspect= */
                    null,  /* includeAttributeSourceAspects= */
                    false,
                    LabelPrinter.legacy()
                )
            pbBuilder.addAttribute(serializedAttribute)
        }

        return pbBuilder.build()
    }

    companion object {
        private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
    }
}
