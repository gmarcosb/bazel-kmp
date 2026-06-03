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
package com.google.devtools.build.lib.starlark.util

import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.pkgcache.LoadingOptions
import java.util.*

/** Helper base class for testing the use of Starlark-style flags.  */
open class StarlarkOptionsTestCase : BuildViewTestCase() {
    protected var optionsParser: OptionsParser? = null
    private var starlarkOptionsParser: StarlarkOptionsParser? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        optionsParser =
            OptionsParser.builder()
                .optionsClasses(
                    Iterables.concat(
                        REQUIRED_OPTIONS_CLASSES,
                        ruleClassProvider.getFragmentRegistry().getOptionsClasses()
                    )
                )
                .skipStarlarkOptionPrefixes()
                .build()
        starlarkOptionsParser =
            StarlarkOptionsParser.builder()
                .buildSettingLoader(
                    SkyframeExecutorTargetLoader(
                        skyframeExecutor, PathFragment.EMPTY_FRAGMENT, reporter
                    )
                )
                .nativeOptionsParser(optionsParser)
                .build()
    }

    @Throws(Exception::class)
    protected fun parseStarlarkOptions(options: String, onlyStarlarkParser: Boolean = false): OptionsParsingResult {
        val asList = Arrays.asList<String?>(*options.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
        if (!onlyStarlarkParser) {
            optionsParser.parse(asList)
        }
        assertThat(starlarkOptionsParser.parseGivenArgs(asList)).isTrue()
        return optionsParser
    }

    @Throws(Exception::class)
    protected fun parseStarlarkOptions(
        commandLineOptions: String, bazelrcOptions: String
    ): OptionsParsingResult {
        val commandLineOptionsList =
            Arrays.asList<String?>(*commandLineOptions.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray())
        val bazelrcOptionsList =
            Arrays.asList<String?>(*bazelrcOptions.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
        optionsParser.parse(PriorityCategory.COMMAND_LINE,  /* source= */null, commandLineOptionsList)
        optionsParser.parse(PriorityCategory.RC_FILE, "fake.bazelrc", bazelrcOptionsList)
        assertThat(
            starlarkOptionsParser.parseGivenArgs(
                ImmutableList.builder<String?>()
                    .addAll(commandLineOptionsList)
                    .addAll(bazelrcOptionsList)
                    .build()
            )
        )
            .isTrue()
        return optionsParser
    }

    @Throws(Exception::class)
    private fun writeBuildSetting(type: String?, defaultValue: String?, isFlag: Boolean) {
        val flag = if (isFlag) "True" else "False"

        scratch.file(
            "test/build_setting.bzl",
            "def _build_setting_impl(ctx):",
            "  return []",
            type + "_setting = rule(",
            "  implementation = _build_setting_impl,",
            "  build_setting = config." + type + "(flag=" + flag + ")",
            ")"
        )
        scratch.file(
            "test/BUILD",
            "load('//test:build_setting.bzl', '" + type + "_setting')",
            (type
                    + "_setting(name = 'my_"
                    + type
                    + "_setting', build_setting_default = "
                    + defaultValue
                    + ")")
        )
    }

    @Throws(Exception::class)
    protected fun writeBasicIntFlag() {
        writeBuildSetting("int", "42", true)
    }

    @Throws(Exception::class)
    protected fun writeBasicBoolFlag() {
        writeBuildSetting("bool", "True", true)
    }

    companion object {
        private val REQUIRED_OPTIONS_CLASSES: ImmutableList<Class<out OptionsBase?>?> = ImmutableList.of<E?>(
            PackageOptions::class.java,
            BuildLanguageOptions::class.java,
            KeepGoingOption::class.java,
            LoadingOptions::class.java,
            ClientOptions::class.java,
            UiOptions::class.java,
            CommonCommandOptions::class.java
        )
    }
}
