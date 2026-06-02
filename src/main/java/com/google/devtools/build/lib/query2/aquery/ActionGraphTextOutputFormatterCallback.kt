// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.query2.aquery.AqueryUtils.getActionInputs

/** Output callback for aquery, prints human readable output.  */
internal class ActionGraphTextOutputFormatterCallback(
    eventHandler: ExtendedEventHandler?,
    options: AqueryOptions?,
    out: java.io.OutputStream?,
    accessor: TargetAccessor<ConfiguredTargetValue?>?,
    private val outputType: OutputType,
    actionFilters: AqueryActionFilter?,
    labelPrinter: LabelPrinter
) : AqueryThreadsafeCallback(eventHandler, options, out, accessor) {
    enum class OutputType(formatName: String) {
        TEXT("text"),
        COMMANDS("commands");

        val formatName: String?

        init {
            this.formatName = formatName
        }
    }

    private val actionKeyContext: ActionKeyContext = ActionKeyContext()
    private val actionFilters: AqueryActionFilter?
    private val labelPrinter: LabelPrinter
    private var paramFileNameToContentMap: MutableMap<String?, String?>? = null
        /** Lazy initialization of paramFileNameToContentMap.  */
        get() {
            if (field == null) {
                field = HashMap<String?, String?>()
            }
            return field
        }

    init {
        this.actionFilters = actionFilters
        this.labelPrinter = labelPrinter
    }

    val name: String?
        get() = outputType.formatName

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun processOutput(partialResult: Iterable<ConfiguredTargetValue>) {
        try {
            // Enabling includeParamFiles should enable includeCommandline by default.
            options.setIncludeCommandline(
                options.getIncludeCommandline() || options.getIncludeParamFiles()
            )

            for (configuredTargetValue in partialResult) {
                if (configuredTargetValue !is RuleConfiguredTargetValue) {
                    // We have to include non-rule values in the graph to visit their dependencies, but they
                    // don't have any actions to print out.
                    continue
                }
                for (action in (configuredTargetValue as RuleConfiguredTargetValue).getActions()) {
                    writeAction(action, printStream)
                }
                if (options.getUseAspects()) {
                    for (aspectValue in accessor.getAspectValues(configuredTargetValue)) {
                        if (aspectValue != null) {
                            for (action in aspectValue.getActions()) {
                                writeAction(action, printStream)
                            }
                        }
                    }
                }
            }
        } catch (e: CommandLineExpansionException) {
            throw IOException(e.getMessage())
        } catch (e: net.starlark.java.eval.EvalException) {
            throw IOException(e.getMessage())
        }
    }

    @Throws(
        IOException::class,
        CommandLineExpansionException::class,
        java.lang.InterruptedException::class,
        net.starlark.java.eval.EvalException::class
    )
    private fun writeAction(action: ActionAnalysisMetadata, printStream: PrintStream) {
        if (options.getIncludeParamFiles()
            && action is ParameterFileWriteAction
        ) {
            val fileContent: String? = java.lang.String.join(" \\\n    ", action.getArguments())
            val paramFileName: String? = action.getPrimaryOutput().getExecPathString()

            this.paramFileNameToContentMap!!.put(paramFileName, fileContent)
        }

        if (!AqueryUtils.matchesAqueryFilters(
                action, actionFilters, options.getIncludePrunedInputs()
            )
        ) {
            return
        }

        val stringBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
        when (outputType) {
            com.google.devtools.build.lib.query2.aquery.ActionGraphTextOutputFormatterCallback.OutputType.TEXT -> writeText(
                action,
                stringBuilder
            )

            com.google.devtools.build.lib.query2.aquery.ActionGraphTextOutputFormatterCallback.OutputType.COMMANDS -> writeCommand(
                action,
                stringBuilder
            )
        }
        printStream.write(stringBuilder.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
    }

    @Throws(
        IOException::class,
        CommandLineExpansionException::class,
        java.lang.InterruptedException::class,
        net.starlark.java.eval.EvalException::class
    )
    private fun writeText(action: ActionAnalysisMetadata, stringBuilder: java.lang.StringBuilder) {
        val actionOwner: ActionOwner? = action.getOwner()
        stringBuilder
            .append(action.prettyPrint())
            .append('\n')
            .append("  Mnemonic: ")
            .append(action.getMnemonic())
            .append('\n')

        if (actionOwner != null) {
            val configuration: BuildEvent = actionOwner.getBuildConfigurationEvent()
            val configProto: BuildEventStreamProtos.Configuration =
                configuration.asStreamProto( /*context=*/null).getConfiguration()

            stringBuilder
                .append("  Target: ")
                .append(labelPrinter.toString(actionOwner.getLabel()))
                .append('\n')
                .append("  Configuration: ")
                .append(configProto.getMnemonic())
                .append('\n')
            if (actionOwner.getExecutionPlatform() != null) {
                stringBuilder
                    .append("  Execution platform: ")
                    .append(labelPrinter.toString(actionOwner.getExecutionPlatform().label()))
                    .append("\n")
            }

            // In the case of aspect-on-aspect, AspectDescriptors are listed in
            // topological order of the dependency graph.
            // e.g. [A -> B] would imply that aspect A is applied on top of aspect B.
            val aspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?> =
                actionOwner.getAspectDescriptors().reverse()
            if (!aspectDescriptors.isEmpty()) {
                stringBuilder
                    .append("  AspectDescriptors: [")
                    .append(
                        aspectDescriptors.stream()
                            .map<String?> { aspectDescriptor: AspectDescriptor? ->
                                val aspectDescription: java.lang.StringBuilder = java.lang.StringBuilder()
                                aspectDescription
                                    .append(aspectDescriptor.getAspectClass().getName())
                                    .append('(')
                                    .append(
                                        aspectDescriptor
                                            .getParameters()
                                            .getAttributes()
                                            .entries()
                                            .stream()
                                            .map(
                                                { parameter ->
                                                    (parameter.getKey()
                                                            + "='"
                                                            + parameter.getValue()
                                                            + "'")
                                                })
                                            .collect(Collectors.joining(", "))
                                    )
                                    .append(')')
                                aspectDescription.toString()
                            }
                            .collect(Collectors.joining("\n    -> ")))
                    .append("]\n")
            }
        }

        if (action is ActionExecutionMetadata) {
            stringBuilder
                .append("  ActionKey: ")
                .append(
                    action.getKey(actionKeyContext,  /* inputMetadataProvider= */null)
                )
                .append('\n')
        }

        if (options.getIncludeArtifacts()) {
            val inputs: NestedSet<Artifact?> = getActionInputs(action, options.getIncludePrunedInputs())

            stringBuilder
                .append("  Inputs: [")
                .append(
                    inputs.toList().stream()
                        .map({ input -> internalToEscapedUnicode(input.getExecPathString()) })
                        .sorted()
                        .collect(Collectors.joining(", "))
                )
                .append("]\n")

            stringBuilder
                .append("  Outputs: [")
                .append(
                    action.getOutputs().stream()
                        .map(
                            { output ->
                                internalToEscapedUnicode(
                                    if (output.isTreeArtifact())
                                        output.getExecPathString() + " (TreeArtifact)"
                                    else
                                        output.getExecPathString()
                                )
                            })
                        .sorted()
                        .collect(Collectors.joining(", "))
                )
                .append("]\n")
        }

        if (action is AbstractAction) {
            // TODO(twerth): This handles the fixed environment. We probably want to output the inherited
            // environment as well.
            val fixedEnvironment: Iterable<MutableMap.MutableEntry<String?, String?>?> =
                action.getEnvironment().getFixedEnv().entrySet()
            if (!com.google.common.collect.Iterables.isEmpty(fixedEnvironment)) {
                stringBuilder
                    .append("  Environment: [")
                    .append(
                        com.google.common.collect.Streams.stream<MutableMap.MutableEntry<String?, String?>?>(
                            fixedEnvironment
                        )
                            .map<String?> { environmentVariable: MutableMap.MutableEntry<String?, String?>? ->
                                internalToEscapedUnicode(
                                    (environmentVariable!!.key
                                            + "="
                                            + environmentVariable.value)
                                )
                            }
                            .sorted()
                            .collect(Collectors.joining(", ")))
                    .append("]\n")
            }
        }
        if (options.getIncludeCommandline() && action is CommandAction) {
            stringBuilder
                .append("  Command Line: ")
                .append(
                    CommandFailureUtils.describeCommand(
                        CommandDescriptionForm.COMPLETE,  /* prettyPrintArgs= */
                        true,
                        (action as CommandAction)
                            .getArguments().stream()
                            .map({ a -> internalToEscapedUnicode(a) })
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()),  /* environment= */
                        null,  /* environmentVariablesToClear= */
                        null,  /* cwd= */
                        null,
                        action.getOwner().getConfigurationChecksum(),
                        if (action.getExecutionPlatform() != null)
                            action.getExecutionPlatform().label()
                        else
                            null,  /* spawnRunner= */
                        null
                    )
                )
                .append("\n")
        }

        if (options.getIncludeParamFiles()) {
            // Assumption: if an Action takes a param file as an input, it will be used
            // to provide params to the command.
            for (input in getActionInputs(action, options.getIncludePrunedInputs()).toList()) {
                val inputFileName: String? = input.getExecPathString()
                if (this.paramFileNameToContentMap!!.containsKey(inputFileName)) {
                    stringBuilder
                        .append("  Params File Content (")
                        .append(inputFileName)
                        .append("):\n    ")
                        .append(this.paramFileNameToContentMap!!.get(inputFileName))
                        .append("\n")
                }
            }
        }
        val executionInfo: MutableMap<String?, String?> = action.getExecutionInfo()
        if (!executionInfo.isEmpty()) {
            stringBuilder
                .append("  ExecutionInfo: {")
                .append(
                    executionInfo.entries.stream()
                        .sorted(java.util.Map.Entry.comparingByKey<String?, String?>())
                        .map<String?> { e: MutableMap.MutableEntry<String?, String?>? ->
                            String.format(
                                "%s: %s",
                                ShellEscaper.escapeString(e!!.key),
                                ShellEscaper.escapeString(e.value)
                            )
                        }
                        .collect(Collectors.joining(", ")))
                .append("}\n")
        }

        if (action is TemplateExpansionAction) {
            stringBuilder
                .append("  Template: ")
                .append(AqueryUtils.getTemplateContent(action))
                .append("\n")

            stringBuilder.append("  Substitutions: [\n")
            for (substitution in action.getSubstitutions()) {
                stringBuilder
                    .append("    {")
                    .append(substitution.getKey())
                    .append(": ")
                    .append(substitution.getValue())
                    .append("}\n")
            }
            stringBuilder.append("  ]\n")
        }

        if (action is AbstractFileWriteAction.FileContentsProvider) {
            stringBuilder.append(java.lang.String.format("  IsExecutable: %b\n", action.makeExecutable()))
            if (options.getIncludeFileWriteContents()) {
                val contents: String = action.getFileContents(eventHandler)
                stringBuilder
                    .append("  FileWriteContents: [")
                    .append(
                        java.util.Base64.getEncoder()
                            .encodeToString(contents.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    )
                    .append("]\n")
            }
        }

        if (action is UnresolvedSymlinkAction) {
            stringBuilder
                .append("  UnresolvedSymlinkTarget: ")
                .append((action as UnresolvedSymlinkAction).getTarget())
                .append("\n")
        }

        stringBuilder.append('\n')
    }

    @Throws(
        IOException::class,
        CommandLineExpansionException::class,
        java.lang.InterruptedException::class,
        net.starlark.java.eval.EvalException::class
    )
    private fun writeCommand(action: ActionAnalysisMetadata, stringBuilder: java.lang.StringBuilder) {
        if (action !is CommandAction) {
            return
        }

        var first = true
        for (arg in (action as CommandAction)
            .getArguments().stream()
            .map({ a -> internalToEscapedUnicode(a) })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())) {
            if (!first) {
                stringBuilder.append(' ')
            }
            ScriptUtil.emitCommandElement( /* message= */
                stringBuilder,  /* commandElement= */arg,  /* isBinary= */first
            )
            first = false
        }
        stringBuilder.append('\n')
    }

    companion object {
        /**
         * Convert an internal string (see [com.google.devtools.build.lib.util.StringEncoding]) to a
         * Unicode string with any character outside the basic printable ASCII range escaped.
         * 
         * 
         * Characters other than printable ASCII but within the Basic Multilingual Plane are formatted
         * with `\\uXXXX`. Characters outside the BMP are formatted as `\\UXXXXXXXX`.
         */
        fun internalToEscapedUnicode(internal: String): String {
            if (internal.chars().allMatch(IntPredicate { c: Int -> c >= 0x20 && c < 0x7F })) {
                return internal
            }

            val unicode: String = StringEncoding.internalToUnicode(internal)
            val sb: java.lang.StringBuilder = java.lang.StringBuilder(unicode.length * 8)
            unicode
                .codePoints()
                .forEach(
                    IntConsumer { c: Int ->
                        if (c >= 0x20 && c < 0x7F) {
                            sb.appendCodePoint(c)
                        } else if (c <= 0xFFFF) {
                            sb.append(String.format("\\u%04X", c))
                        } else {
                            sb.append(String.format("\\U%08X", c))
                        }
                    })
            return sb.toString()
        }
    }
}
