// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.BazelServices.BAZEL_SERVICES

/** Tests the handling of rc-options in [BlazeCommandDispatcher].  */
@RunWith(JUnit4::class)
class BlazeCommandDispatcherRcoptionsTest {
    /** Example options to be used by the tests.  */
    @OptionsClass
    abstract class FooOptions : OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "numoption",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "0"
        )
        abstract val numOption: Int

        @get:com.google.devtools.common.options.Option(
            name = "stringoption",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "[unspecified]"
        )
        abstract val stringOption: String?
    }

    @Command(
        name = "reportnum",
        options = [com.google.devtools.build.lib.runtime.BlazeCommandDispatcherRcoptionsTest.FooOptions::class],
        shortDescription = "",
        help = ""
    )
    private class ReportNumCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
            val fooOptions: FooOptions? =
                options.getOptions<FooOptions?>(com.google.devtools.build.lib.runtime.BlazeCommandDispatcherRcoptionsTest.FooOptions::class.java)
            env.getReporter().getOutErr().printOut(fooOptions!!.numOption.toString())
            return BlazeCommandResult.success()
        }
    }

    @Command(
        name = "reportall",
        options = [com.google.devtools.build.lib.runtime.BlazeCommandDispatcherRcoptionsTest.FooOptions::class],
        shortDescription = "",
        help = ""
    )
    private open class ReportAllCommand : BlazeCommand {
        public override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
            val fooOptions: FooOptions? =
                options.getOptions<FooOptions?>(com.google.devtools.build.lib.runtime.BlazeCommandDispatcherRcoptionsTest.FooOptions::class.java)
            env.getReporter()
                .getOutErr()
                .printOut(fooOptions!!.numOption.toString() + " " + fooOptions.stringOption)
            return BlazeCommandResult.success()
        }
    }

    @Command(
        name = "reportallinherited",
        options = [com.google.devtools.build.lib.runtime.BlazeCommandDispatcherRcoptionsTest.FooOptions::class],
        shortDescription = "",
        help = "",
        inheritsOptionsFrom = ReportAllCommand::class
    )
    private class ReportAllInheritedCommand : ReportAllCommand()

    private val scratch: Scratch = Scratch()
    private val outErr: RecordingOutErr = RecordingOutErr()
    private val reportNum = ReportNumCommand()
    private val reportAll = ReportAllCommand()
    private val reportAllInherited: ReportAllCommand = ReportAllInheritedCommand()
    private var runtime: BlazeRuntime? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeRuntime() {
        val productName: String = TestConstants.PRODUCT_NAME
        val startupOptionsProvider: OptionsParsingResult? =
            OptionsParser.builder().optionsClasses(BlazeServerStartupOptions::class.java).build()
        for (service in BAZEL_SERVICES) {
            service.globalInit(startupOptionsProvider, BAZEL_SERVICES)
        }
        val serverDirectories: ServerDirectories =
            ServerDirectories(
                scratch.dir("install_base"),
                scratch.dir("output_base"),
                scratch.dir("user_output_root")
            )
        this.runtime =
            Builder()
                .setFileSystem(scratch.getFileSystem())
                .setProductName(productName)
                .setServerDirectories(serverDirectories)
                .setStartupOptionsProvider(startupOptionsProvider)
                .addBlazeModule(
                    object : BlazeModule() {
                        public override fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder) {
                            // We must add these options so that the defaults package can be created.
                            builder.addConfigurationOptions(CoreOptions::class.java)
                            // The defaults package asserts that it is not empty, so we provide options.
                            builder.addConfigurationOptions(MockFragmentOptions::class.java)
                            // The tools repository is needed for createGlobals
                            builder.setToolsRepository(TestConstants.TOOLS_REPOSITORY)
                        }
                    })
                .build()

        val directories: BlazeDirectories =
            BlazeDirectories(serverDirectories, scratch.dir("pkg"), productName)
        this.runtime.initWorkspace(directories,  /* binTools= */null)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommonUsed() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/home/jrluser/.blazerc", "--default_override=0:common=--numoption=99"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(reportNum))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("reportnum")
        cmdLine.addAll(blazercOpts)

        dispatch.exec(cmdLine, "test", outErr)
        val out: String? = outErr.outAsLatin1()
        Truth.assertWithMessage("Common options should be used").that(out).isEqualTo("99")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpecificOptionsWin() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=0:reportnum=--numoption=42",
                "--default_override=0:common=--numoption=99"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(reportNum))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("reportnum")
        cmdLine.addAll(blazercOpts)

        dispatch.exec(cmdLine, "test", outErr)
        val out: String? = outErr.outAsLatin1()
        Truth.assertWithMessage("Specific options should dominate common options").that(out).isEqualTo("42")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpecificOptionsWinOtherOrder() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=0:common=--numoption=99",
                "--default_override=0:reportnum=--numoption=42"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(reportNum))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("reportnum")
        cmdLine.addAll(blazercOpts)

        dispatch.exec(cmdLine, "test", outErr)
        val out: String? = outErr.outAsLatin1()
        Truth.assertWithMessage("Specific options should dominate common options").that(out).isEqualTo("42")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsCombined() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/etc/bazelrc",
                "--default_override=0:common=--stringoption=foo",
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=1:common=--numoption=99"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(reportNum, reportAll))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("reportall")
        cmdLine.addAll(blazercOpts)

        dispatch.exec(cmdLine, "test", outErr)
        val out: String? = outErr.outAsLatin1()
        Truth.assertWithMessage("Options should get accumulated over different rc files")
            .that(out)
            .isEqualTo("99 foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsCombinedWithOverride() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/etc/bazelrc",
                "--default_override=0:common=--stringoption=foo",
                "--default_override=0:common=--numoption=42",
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=1:common=--numoption=99"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(reportNum, reportAll))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("reportall")
        cmdLine.addAll(blazercOpts)

        dispatch.exec(cmdLine, "test", outErr)
        val out: String? = outErr.outAsLatin1()
        Truth.assertWithMessage("The more specific rc-file should override").that(out).isEqualTo("99 foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOptionsCombinedWithOverrideOtherName() {
        val blazercOpts: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "--rc_source=/home/jrluser/.blazerc",
                "--default_override=0:common=--stringoption=foo",
                "--default_override=0:common=--numoption=42",
                "--rc_source=/etc/bazelrc",
                "--default_override=1:common=--numoption=99"
            )

        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(reportNum, reportAll))
        val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
        val cmdLine: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("reportall")
        cmdLine.addAll(blazercOpts)

        dispatch.exec(cmdLine, "test", outErr)
        val out: String? = outErr.outAsLatin1()
        Truth.assertWithMessage("The more specific rc-file should override irrespective of name")
            .that(out)
            .isEqualTo("99 foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInheritedOptionsWithSpecificOverride() {
        val blazercOpts: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<String?>?> =
            com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableList<String?>?>(
                com.google.common.collect.ImmutableList.of<String?>(
                    "--rc_source=/doesnt/matter/0/bazelrc",
                    "--default_override=0:common=--stringoption=common",
                    "--default_override=0:common=--numoption=42"
                ),
                com.google.common.collect.ImmutableList.of<String?>(
                    "--rc_source=/doesnt/matter/1/bazelrc",
                    "--default_override=0:reportall=--stringoption=reportall"
                ),
                com.google.common.collect.ImmutableList.of<String?>(
                    "--rc_source=/doesnt/matter/2/bazelrc",
                    "--default_override=0:reportallinherited=--stringoption=reportallinherited"
                )
            )

        runtime.overrideCommands(
            com.google.common.collect.ImmutableList.of<E?>(
                reportNum,
                reportAll,
                reportAllInherited
            )
        )
        for (e in com.google.common.collect.Collections2.permutations<com.google.common.collect.ImmutableList<String?>?>(
            blazercOpts
        )) {
            outErr.reset()
            val dispatch: BlazeCommandDispatcher = BlazeCommandDispatcher(runtime)
            val cmdLine: MutableList<String?> =
                com.google.common.collect.Lists.newArrayList<String?>("reportallinherited")
            val orderedOpts: MutableList<String?> = com.google.common.collect.ImmutableList.copyOf<String?>(
                com.google.common.collect.Iterables.concat<String?>(e)
            )
            cmdLine.addAll(orderedOpts)

            dispatch.exec(cmdLine, "test", outErr)
            val out: String? = outErr.outAsLatin1()
            Truth.assertWithMessage(
                "The more specific option should override, irrespective of source file or order. %s",
                orderedOpts
            )
                .that(out)
                .isEqualTo("42 reportallinherited")
        }
    }

    /** Options class for testing, so that defaults package has some content.  */
    @OptionsClass
    abstract class MockFragmentOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "fake_opt",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "false"
        )
        abstract val fakeOpt: Boolean
    }
}
