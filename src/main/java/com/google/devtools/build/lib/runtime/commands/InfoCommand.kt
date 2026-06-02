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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** Implementation of 'blaze info'.  */
@Command(
    name = "info",
    buildPhase = NONE,
    allowResidue = true,
    binaryStdOut = true,
    help = "resource:info.txt",
    shortDescription = "Displays runtime info about the %{product} server.",
    options = [com.google.devtools.build.lib.runtime.commands.InfoCommand.Options::class],
    completion = "info-key",
    inheritsOptionsFrom = [BuildCommand::class]
)
class InfoCommand : BlazeCommand {
    /** Options for the info command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "show_make_env",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "Include the \"Make\" environment in the output."
        )
        abstract val showMakeEnvironment: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "info_output_type",
            defaultValue = "stdout",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            converter = InfoItemOutputTypeConverter::class,
            help = ("If stdout, results are directly printed to the console. If response_proto, the info"
                    + " command results are packed in response extensions.")
        )
        abstract val infoOutputType: InfoItemOutputType?
    }

    private class InfoItemOutputTypeConverter protected constructor() :
        com.google.devtools.common.options.EnumConverter<InfoItemOutputType?>(
            InfoItemOutputType::class.java,
            "InfoItem output type"
        )

    /**
     * Unchecked variant of [AbruptExitException]. Below, we need to throw from the Supplier
     * interface, which does not allow checked exceptions.
     */
    private class AbruptExitRuntimeException(exitCode: DetailedExitCode?) : java.lang.RuntimeException() {
        private val detailedExitCode: DetailedExitCode?

        init {
            this.detailedExitCode = exitCode
        }

        fun getDetailedExitCode(): DetailedExitCode? {
            return detailedExitCode
        }
    }

    private val infoItemHandlerFactory: InfoItemHandlerFactory

    @com.google.common.annotations.VisibleForTesting
    constructor(infoItemHandlerFactory: InfoItemHandlerFactory) {
        this.infoItemHandlerFactory = infoItemHandlerFactory
    }

    constructor() {
        this.infoItemHandlerFactory = InfoItemHandlerFactoryImpl()
    }

    public override fun exec(
        env: CommandEnvironment, optionsParsingResult: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val runtime: BlazeRuntime = env.getRuntime()
        env.getReporter().switchToAnsiAllowingHandler()
        val infoOptions: Options? =
            optionsParsingResult.getOptions<Options?>(com.google.devtools.build.lib.runtime.commands.InfoCommand.Options::class.java)
        // Creating a BuildConfigurationValue is expensive and often unnecessary. Delay the creation
        // until it is needed. We memoize so that it's cached intra-command (it's still created freshly
        // on every command since the configuration can change across commands).
        val configurationSupplier: com.google.common.base.Supplier<BuildConfigurationValue?> =
            com.google.common.base.Suppliers.memoize<T?>(
                com.google.common.base.Supplier {
                    try {
                        com.google.devtools.build.lib.profiler.Profiler.instance()
                            .profile("Creating BuildConfigurationValue").use { c ->
                                // In order to be able to answer configuration-specific queries, we need to set up
                                // the package path. Since info inherits all the build options, all the necessary
                                // information is available here.
                                ensureSyncPackageLoading(env, optionsParsingResult)
                                // TODO(bazel-team): What if there are multiple configurations? [multi-config]
                                val buildOptions: BuildOptions? = runtime.createBuildOptions(optionsParsingResult)
                                env.getSkyframeExecutor().setBaselineConfiguration(buildOptions, env.getReporter())
                                return@memoize env.getSkyframeExecutor()
                                    .getConfiguration(env.getReporter(), buildOptions,  /* keepGoing= */true)
                            }
                    } catch (e: InvalidConfigurationException) {
                        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                        throw AbruptExitRuntimeException(e.getDetailedExitCode())
                    } catch (e: AbruptExitException) {
                        throw AbruptExitRuntimeException(e.getDetailedExitCode())
                    } catch (e: java.lang.InterruptedException) {
                        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error("interrupted"))
                        throw AbruptExitRuntimeException(
                            InterruptedFailureDetails.detailedExitCode(
                                "command interrupted while syncing package loading"
                            )
                        )
                    }
                })

        val items: MutableMap<String?, InfoItem> = getInfoItemMap(env, optionsParsingResult)
        val residue: MutableList<String?> = optionsParsingResult.getResidue()

        try {
            infoItemHandlerFactory.create(
                env, infoOptions!!.infoOutputType,  /* printKeys= */residue.size != 1
            ).use { infoItemHandler ->
                if (infoOptions.showMakeEnvironment) {
                    val makeEnv: MutableMap<String?, String?> = configurationSupplier.get().getMakeEnvironment()
                    for (entry in makeEnv.entries) {
                        val item: InfoItem = MakeInfoItem(entry.key, entry.value)
                        items.put(item.name, item)
                    }
                }
                env.getEventBus().post(NoBuildEvent())
                if (!residue.isEmpty()) {
                    val unknownKeysBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
                        com.google.common.collect.ImmutableSet.builder<String?>()
                    for (key in residue) {
                        if (items.containsKey(key)) {
                            com.google.devtools.build.lib.profiler.Profiler.instance().profile(key + ".infoItem")
                                .use { c ->
                                    val infoItem: InfoItem = items.get(key)
                                    if (infoItem.needsSyncPackageLoading()) {
                                        ensureSyncPackageLoading(env, optionsParsingResult)
                                    }
                                    val value: ByteArray? = infoItem.get(configurationSupplier, env)
                                    infoItemHandler.addInfoItem(key, value)
                                }
                        } else {
                            unknownKeysBuilder.add(key)
                        }
                    }
                    val unknownKeys: com.google.common.collect.ImmutableSet<String?> = unknownKeysBuilder.build()
                    if (!unknownKeys.isEmpty()) {
                        val message =
                            ("unknown key(s): "
                                    + unknownKeys.stream()
                                .map<String?> { key: String? ->
                                    "'%s'%s".formatted(
                                        key,
                                        net.starlark.java.spelling.SpellChecker.didYouMean(key, items.keys)
                                    )
                                }
                                .collect(Collectors.joining(", ")))
                        env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                        return createFailureResult(
                            message,
                            ExitCode.COMMAND_LINE_ERROR,
                            FailureDetails.InfoCommand.Code.KEY_NOT_RECOGNIZED
                        )
                    }
                } else { // print them all
                    val unused: BuildConfigurationValue? = configurationSupplier.get() // We'll need this later anyway
                    for (infoItem in items.values) {
                        if (infoItem.isHidden()) {
                            continue
                        }
                        if (infoItem.needsSyncPackageLoading()) {
                            ensureSyncPackageLoading(env, optionsParsingResult)
                        }
                        com.google.devtools.build.lib.profiler.Profiler.instance().profile(infoItem.name + ".infoItem")
                            .use { c ->
                                infoItemHandler.addInfoItem(
                                    infoItem.name, infoItem.get(configurationSupplier, env)
                                )
                            }
                    }
                }
            }
        } catch (e: AbruptExitException) {
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        } catch (e: AbruptExitRuntimeException) {
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        } catch (e: IOException) {
            return createFailureResult(
                "Cannot write info block: " + e.message,
                ExitCode.LOCAL_ENVIRONMENTAL_ERROR,
                FailureDetails.InfoCommand.Code.ALL_INFO_WRITE_FAILURE
            )
        } catch (e: java.lang.InterruptedException) {
            return BlazeCommandResult.detailedExitCode(
                InterruptedFailureDetails.detailedExitCode("info interrupted")
            )
        }
        return BlazeCommandResult.success()
    }

    companion object {
        @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
        private fun ensureSyncPackageLoading(
            env: CommandEnvironment,
            options: com.google.devtools.common.options.OptionsProvider?
        ) {
            if (!env.hasSyncedPackageLoading()) {
                env.syncPackageLoading(options)
            }
        }

        private fun createFailureResult(
            message: String?, exitCode: ExitCode?, detailedCode: FailureDetails.InfoCommand.Code?
        ): BlazeCommandResult {
            return BlazeCommandResult.detailedExitCode(
                DetailedExitCode.of(
                    exitCode,
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setInfoCommand(FailureDetails.InfoCommand.newBuilder().setCode(detailedCode))
                        .build()
                )
            )
        }

        private fun getHardwiredInfoItemMap(
            commandOptions: com.google.devtools.common.options.OptionsParsingResult?, productName: String?
        ): MutableMap<String, InfoItem?> {
            val hardwiredInfoItems: MutableList<InfoItem> =
                com.google.common.collect.ImmutableList.of<InfoItem>(
                    WorkspaceInfoItem(),
                    InstallBaseInfoItem(),
                    InstallMd5InfoItem(),
                    OutputBaseInfoItem(productName),
                    ExecutionRootInfoItem(),
                    OutputPathInfoItem(),
                    ClientEnv(),
                    BlazeBinInfoItem(productName),
                    BlazeGenfilesInfoItem(productName),
                    BlazeTestlogsInfoItem(productName),
                    ReleaseInfoItem(productName),
                    ServerPidInfoItem(productName),
                    ServerLogInfoItem(productName),
                    PackagePathInfoItem(commandOptions),
                    UsedHeapSizeInfoItem(),
                    UsedHeapSizeAfterGcInfoItem(),
                    CommittedHeapSizeInfoItem(),
                    MaxHeapSizeInfoItem(),
                    GcTimeInfoItem(),
                    GcCountInfoItem(),
                    JavaRuntimeInfoItem(),
                    JavaVirtualMachineInfoItem(),
                    JavaHomeInfoItem(),
                    CharacterEncodingInfoItem(),
                    DefaultsPackageInfoItem(),
                    BuildLanguageInfoItem(),
                    DefaultPackagePathInfoItem(commandOptions),
                    StarlarkSemanticsInfoItem(commandOptions),
                    WorkerMetricsInfoItem(),
                    LocalResourcesInfoItem()
                )
            val result: com.google.common.collect.ImmutableMap.Builder<String?, InfoItem?> =
                com.google.common.collect.ImmutableMap.Builder<String?, InfoItem?>()
            for (item in hardwiredInfoItems) {
                result.put(item.name, item)
            }
            return result.buildOrThrow()
        }

        fun getHardwiredInfoItemNames(productName: String?): MutableList<String?> {
            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()
            for (name in getHardwiredInfoItemMap(null, productName).keys) {
                result.add(name)
            }
            return result.build()
        }

        fun getInfoItemMap(
            env: CommandEnvironment, optionsParsingResult: com.google.devtools.common.options.OptionsParsingResult?
        ): MutableMap<String?, InfoItem> {
            val items: MutableMap<String?, InfoItem> = TreeMap<Any?, Any?>(env.getRuntime().getInfoItems())
            items.putAll(getHardwiredInfoItemMap(optionsParsingResult, env.getRuntime().productName))
            return items
        }
    }
}
