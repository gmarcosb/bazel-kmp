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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Helper class for setting up tests that make use of [BlazeOptionHandler].  */
internal class BlazeOptionHandlerTestHelper @kotlin.jvm.JvmOverloads constructor(
    optionsClasses: MutableList<java.lang.Class<out OptionsBase?>?>?,
    allowResidue: Boolean,
    aliasFlag: String? = null,
    skipStarlarkPrefixes: Boolean = false
) {
    private val scratch: Scratch = Scratch()
    val eventHandler: StoredEventHandler = StoredEventHandler()
    private val parser: OptionsParser?
    private val optionHandler: BlazeOptionHandler

    init {
        parser = createOptionsParser(optionsClasses, allowResidue, aliasFlag, skipStarlarkPrefixes)

        val productName: String = TestConstants.PRODUCT_NAME
        val serverDirectories: ServerDirectories =
            ServerDirectories(
                scratch.dir("install_base"), scratch.dir("output_base"), scratch.dir("user_root")
            )

        val runtime: BlazeRuntime =
            Builder()
                .setFileSystem(scratch.getFileSystem())
                .setServerDirectories(serverDirectories)
                .setProductName(productName)
                .setStartupOptionsProvider(
                    OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
                )
                .addBlazeModule(BazelRulesModule())
                .build()
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(MockBuildCommand()))

        val directories: BlazeDirectories =
            BlazeDirectories(
                serverDirectories,
                scratch.dir("workspace"),
                productName
            )
        runtime.initWorkspace(directories,  /*binTools=*/null)

        optionHandler =
            BlazeOptionHandler(
                runtime,
                runtime.getWorkspace(),
                MockBuildCommand(),
                MockBuildCommand::class.java.getAnnotation<A?>(Command::class.java),
                parser,
                InvocationPolicy.getDefaultInstance()
            )
    }

    val optionsParser: OptionsParser?
        get() = parser

    fun getOptionHandler(): BlazeOptionHandler {
        return optionHandler
    }

    /** Custom command for testing.  */
    @Command(
        name = "build",
        shortDescription = "mock build desc",
        help = "mock build help",
        options = [com.google.devtools.common.options.TestOptions::class]
    )
    protected class MockBuildCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment?, options: OptionsParsingResult?): BlazeCommandResult? {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun editOptions(optionsParser: OptionsParser?) {}
    }

    companion object {
        private fun createOptionsParser(
            optionsClasses: MutableList<java.lang.Class<out OptionsBase?>?>?,
            allowResidue: Boolean,
            aliasFlag: String?,
            skipStarlarkPrefixes: Boolean
        ): OptionsParser? {
            val optionsParserBuilder: com.google.devtools.common.options.OptionsParser.Builder =
                OptionsParser.builder()
                    .optionsClasses(optionsClasses)
                    .allowResidue(allowResidue)
                    .withAliasFlag(aliasFlag)

            if (skipStarlarkPrefixes) {
                optionsParserBuilder.skipStarlarkOptionPrefixes()
            }

            return optionsParserBuilder.build()
        }
    }
}
