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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.analysis.AnalysisProtosV2

/** Proto output formatter for cquery results.  */
internal class ProtoOutputFormatterCallback(
    eventHandler: ExtendedEventHandler?,
    options: CqueryOptions?,
    out: java.io.OutputStream?,
    skyframeExecutor: SkyframeExecutor,
    accessor: TargetAccessor<CqueryNode?>?,
    resolver: AspectResolver?,
    private val outputType: OutputType,
    labelPrinter: LabelPrinter
) : CqueryThreadsafeCallback(eventHandler, options, out, skyframeExecutor, accessor,  /* uniquifyResults= */false) {
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
    }

    private class ConfigurationCache(configurationGetter: java.util.function.Function<BuildConfigurationKey?, BuildConfigurationValue?>) {
        private val cache: MutableMap<BuildConfigurationValue?, Int?> = LinkedHashMap<BuildConfigurationValue?, Int?>()
        private val configurationGetter: java.util.function.Function<BuildConfigurationKey?, BuildConfigurationValue?>

        init {
            this.configurationGetter = configurationGetter
        }

        fun getId(buildConfigurationValue: BuildConfigurationValue?): Int {
            return cache.computeIfAbsent(
                buildConfigurationValue,
                java.util.function.Function { event: BuildConfigurationValue? -> cache.size() + 1 })
        }

        fun getId(options: BuildOptions?): Int {
            val configurationValue: BuildConfigurationValue? =
                configurationGetter.apply(BuildConfigurationKey.create(options))
            return getId(configurationValue)
        }

        val configurations: com.google.common.collect.ImmutableList<Configuration?>
            get() = cache.entrySet().stream()
                .map<Any?>(java.util.function.Function { v: MutableMap.MutableEntry<BuildConfigurationValue?, Int?>? ->
                    createConfigurationProto(
                        v.getKey(),
                        v.getValue()
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

        fun createConfigurationProto(
            configurationValue: BuildConfigurationValue, id: Int
        ): Configuration {
            val configurationForOutput: ConfigurationForOutput =
                ConfigurationForOutput.getConfigurationForOutput(configurationValue)
            val configBuilder: Configuration.Builder = Configuration.newBuilder()

            for (fragmentForOutput in configurationForOutput.getFragments()) {
                val fragment: AnalysisProtosV2.Fragment.Builder = AnalysisProtosV2.Fragment.newBuilder()
                fragment.setName(fragmentForOutput.name)
                fragmentForOutput.fragmentOptions.forEach(fragment::addFragmentOptionNames)
                configBuilder.addFragments(fragment)
            }

            for (fragmentOptionsForOutput in configurationForOutput.getFragmentOptions()) {
                val fragmentOptions: AnalysisProtosV2.FragmentOptions.Builder =
                    AnalysisProtosV2.FragmentOptions.newBuilder()
                        .setName(fragmentOptionsForOutput.name)
                for (option in fragmentOptionsForOutput.getOptions().entrySet()) {
                    val optionProto: AnalysisProtosV2.Option? =
                        AnalysisProtosV2.Option.newBuilder()
                            .setName(option.getKey())
                            .setValue(option.getValue())
                            .build()
                    fragmentOptions.addOptions(optionProto)
                }
                configBuilder.addFragmentOptions(fragmentOptions.build())
            }

            val checksum: String? = configurationValue.getEventId().getConfiguration().getId()
            val configProto: BuildEventStreamProtos.Configuration =
                configurationValue
                    .toBuildEvent()
                    .asStreamProto( /* unusedConverters= */null)
                    .getConfiguration()

            return configBuilder
                .setChecksum(checksum)
                .setMnemonic(configProto.getMnemonic())
                .setPlatformName(configProto.getPlatformName())
                .setId(id)
                .setIsTool(configProto.getIsTool())
                .build()
        }
    }

    private var cqueryResultBuilder: CqueryResult.Builder? = null
    private val resolver: AspectResolver?
    private val skyframeExecutor: SkyframeExecutor
    private val configurationCache =
        ConfigurationCache(java.util.function.Function { configKey: BuildConfigurationKey? ->
            this.getConfiguration(configKey)
        })
    private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer()

    private val labelPrinter: LabelPrinter
    private var currentTarget: CqueryNode? = null

    init {
        this.skyframeExecutor = skyframeExecutor
        this.resolver = resolver
        this.labelPrinter = labelPrinter
    }

    override fun start() {
        cqueryResultBuilder = AnalysisProtosV2.CqueryResult.newBuilder()
    }

    @Throws(IOException::class)
    override fun close(failFast: Boolean) {
        if (failFast || printStream == null) {
            return
        }

        // There are a few cases that affect the shape of the output:
        //   1. --output=proto|textproto|jsonproto --proto:include_configurations =>
        //        Writes a single CqueryResult containing all the ConfiguredTarget(s) and
        //        Configuration(s) in the specified output format.
        //   2. --output=streamed_proto --proto:include_configurations =>
        //        Writes multiple length delimited CqueryResult protos, each containing a single
        //        ConfiguredTarget or Configuration.
        //   3. --output=proto|textproto|jsonproto --noproto:include_configurations =>
        //        Writes a single QueryResult containing all the corresponding Target(s) in the
        //        specified output format.
        //   4.--output=streamed_proto --noproto:include_configurations =>
        //        Writes multiple length delimited QueryResult protos, each containing a single Target.
        when (outputType) {
            com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.BINARY, com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.TEXT, com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.JSON -> {
                // Only at the end, we write the entire CqueryResult / QueryResult is written all together.
                if (options.getProtoIncludeConfigurations()) {
                    cqueryResultBuilder.addAllConfigurations(configurationCache.configurations)
                }
                writeData(
                    if (options.getProtoIncludeConfigurations())
                        cqueryResultBuilder.build()
                    else
                        queryResultFromCqueryResult(cqueryResultBuilder)
                )
            }

            com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.DELIMITED_BINARY -> {
                if (options.getProtoIncludeConfigurations()) {
                    // The wrapped CqueryResult + ConfiguredTarget are already written in
                    // {@link #processOutput}, so we just need to write the Configuration(s) each wrapped in
                    // a CqueryResult.
                    for (configuration in configurationCache.configurations) {
                        writeData(
                            AnalysisProtosV2.CqueryResult.newBuilder()
                                .addConfigurations(configuration)
                                .build()
                        )
                    }
                }
            }
        }

        outputStream.flush()
        printStream.flush()
    }

    @Throws(IOException::class)
    private fun writeData(message: Message) {
        when (outputType) {
            com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.BINARY -> {
                // Avoid a crash due to a failed precondition check in protobuf.
                if (message.getSerializedSize() < 0) {
                    throw IOException(
                        "--output=proto does not support results larger than 2GB, use --output=streamed_proto"
                                + " instead."
                    )
                }
                message.writeTo(outputStream)
            }

            com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.DELIMITED_BINARY -> message.writeDelimitedTo(
                outputStream
            )

            com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.TEXT -> TextFormat.printer()
                .print(message, printStream)

            com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.JSON -> {
                jsonPrinter.appendTo(message, printStream)
                printStream.append('\n')
            }
        }
    }

    val name: String?
        get() = outputType.formatName()

    @Throws(java.lang.InterruptedException::class, IOException::class)
    override fun processOutput(partialResult: Iterable<CqueryNode>) {
        val formatter = ConfiguredProtoOutputFormatter()
        formatter.setOptions(options, resolver, skyframeExecutor.getDigestFunction().getHashFunction())
        for (keyedConfiguredTarget in partialResult) {
            val builder: AnalysisProtosV2.ConfiguredTarget.Builder =
                AnalysisProtosV2.ConfiguredTarget.newBuilder()
            // Re: testing. Since this formatter relies on the heavily tested ProtoOutputFormatter class
            // for all its work with targets, ProtoOutputFormatterCallbackTest doesn't test any of the
            // logic in this next line. If this were to change (i.e. we manipulate targets any further),
            // we will want to add relevant tests.
            currentTarget = keyedConfiguredTarget
            val target: Target? = accessor.getTarget(keyedConfiguredTarget)
            val targetBuilder: Build.Target.Builder =
                formatter.toTargetProtoBuffer(target, labelPrinter).toBuilder()
            if (target is Rule && Transitions.NONE != options.getTransitions()) {
                // To set configured_rule_input dependencies, use ConfiguredTargetAccessor.getPrerequisites.
                // Note that both that and CqueryTransitionResolver can get a target's direct deps. We use
                // the former because it implements cquery's "canonical" view of the dependency graph, which
                // might not match the underlying Skyframe graph. For example, without
                // QueryEnvironment.Setting#EXPLICIT_ASPECTS, if CT //foo depends on aspect A which has
                // implicit dep //dep, cquery outputs //dep as a direct dep of //foo. Even though this isn't
                // technically true according to the Skyframe graph. If we used CqueryTransitionResolver,
                // which directly queries Skyframe, it wouldn't return //dep.
                //
                // cquery users should always view the graph according to cquery's canonical interpretation.
                for (dep in accessor.getPrerequisites(keyedConfiguredTarget)) {
                    val configuredRuleInput: ConfiguredRuleInput.Builder =
                        Build.ConfiguredRuleInput.newBuilder()
                            .setLabel(labelPrinter.toString(dep.getOriginalLabel()))
                    if (dep.getConfigurationChecksum() != null) {
                        configuredRuleInput
                            .setConfigurationChecksum(dep.getConfigurationChecksum())
                            .setConfigurationId(
                                configurationCache.getId(dep.getConfigurationKey().getOptions())
                            )
                    }
                    targetBuilder.getRuleBuilder().addConfiguredRuleInput(configuredRuleInput)
                }
            }

            builder.setTarget(targetBuilder)

            if (options.getProtoIncludeConfigurations()) {
                val checksum: String? = keyedConfiguredTarget.getConfigurationChecksum()
                builder.setConfiguration(
                    AnalysisProtosV2.Configuration.newBuilder().setChecksum(java.lang.String.valueOf(checksum))
                )

                val configuredTargetKey: ConfiguredTargetKey? =
                    ConfiguredTargetKey.fromConfiguredTarget(keyedConfiguredTarget)
                // Some targets don't have a configuration, e.g. InputFileConfiguredTarget
                if (configuredTargetKey != null) {
                    val configurationKey: BuildConfigurationKey? = configuredTargetKey.getConfigurationKey()
                    if (configurationKey != null) {
                        val configuration: BuildConfigurationValue? = getConfiguration(configurationKey)
                        val id: Int = configurationCache.getId(configuration)
                        builder.setConfigurationId(id)
                    }
                }
            }

            if (outputType == com.google.devtools.build.lib.query2.cquery.ProtoOutputFormatterCallback.OutputType.DELIMITED_BINARY) {
                // If --proto:include_configurations, we wrap the single ConfiguredTarget in a CqueryResult.
                // If --noproto:include_configurations, we wrap the single Target in a QueryResult.
                // Then we write either result delimited to the stream.
                writeData(
                    if (options.getProtoIncludeConfigurations())
                        CqueryResult.newBuilder().addResults(builder).build()
                    else
                        QueryResult.newBuilder().addTarget(builder.getTarget()).build()
                )
            } else {
                // Except --output=streamed_proto, all other output types require they be wrapped in a
                // CqueryResult or QueryResult. So we instead of writing straight to the stream, we
                // aggregate the results in a CqueryResult.Builder before writing in {@link #close}.
                cqueryResultBuilder.addResults(builder.build())
            }
        }
    }

    private inner class ConfiguredProtoOutputFormatter : ProtoOutputFormatter() {
        override fun addAttributes(
            rulePb: Build.Rule.Builder,
            rule: Rule,
            extraDataForAttrHash: Any?,
            labelPrinter: LabelPrinter?
        ) {
            // We know <code>currentTarget</code> will be either an AliasConfiguredTarget or
            // RuleConfiguredTarget,
            // because this method is only triggered in ProtoOutputFormatter.toTargetProtoBuffer when
            // the target in currentTarget is an instanceof Rule.
            val configConditions: com.google.common.collect.ImmutableMap<Label?, ConfigMatchingProvider?>? =
                currentTarget.getConfigConditions()
            val attributeMapper: ConfiguredAttributeMapper =
                ConfiguredAttributeMapper.of(
                    rule,
                    configConditions,
                    currentTarget.getConfigurationKey().getOptionsChecksum(),  /* alwaysSucceed= */
                    false
                )
            for (attr in sortAttributes(rule.getAttributes())) {
                if (!shouldIncludeAttribute(rule, attr)) {
                    continue
                }
                val attributeValue: Any? = attributeMapper.get(attr.name, attr.getType())
                val serializedAttribute: Build.Attribute? =
                    AttributeFormatter.getAttributeProto(
                        attr,
                        attributeValue,
                        rule.isAttributeValueExplicitlySpecified(attr),  /* encodeBooleanAndTriStateAsIntegerAndString= */
                        true,  /* sourceAspect= */
                        null,
                        includeAttributeSourceAspects,
                        labelPrinter
                    )
                rulePb.addAttribute(serializedAttribute)
            }
        }
    }

    companion object {
        private fun queryResultFromCqueryResult(cqueryResult: CqueryResultOrBuilder): QueryResult {
            val queryResult: Build.QueryResult.Builder = Build.QueryResult.newBuilder()
            cqueryResult.getResultsList().forEach({ ct -> queryResult.addTarget(ct.getTarget()) })
            return queryResult.build()
        }

        fun sortAttributes(attributes: Iterable<Attribute?>): MutableList<Attribute> {
            return com.google.common.collect.Ordering.from<Any?>(java.util.Comparator.comparing<Any?, Any?>(Attribute::getName))
                .sortedCopy<Attribute?>(attributes)
        }
    }
}
