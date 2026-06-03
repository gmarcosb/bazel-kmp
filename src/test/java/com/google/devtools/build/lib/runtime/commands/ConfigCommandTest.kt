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
package com.google.devtools.build.lib.runtime.commands

/**
 * Tests for [ConfigCommand] ("`$ blaze config`").
 * 
 * 
 * These tests assume all important testable properties are determined in [ConfigCommand],
 * so the output formatter used doesn't affect those properties. We test with `--output=json
` *  for easy parsing.
 */
@RunWith(JUnit4::class)
class ConfigCommandTest : BuildIntegrationTestCase() {
    private var dispatcher: BlazeCommandDispatcher? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun init() {
        runtime.overrideCommands(com.google.common.collect.ImmutableList.of<E?>(BuildCommand(), ConfigCommand()))
        dispatcher = BlazeCommandDispatcher(runtime)
        write(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = ["//..."],
        )
        
        """.trimIndent()
        )
        write(
            "test/defs.bzl",
            """
        def _simple_rule_impl(ctx):
            pass

        simple_rule = rule(
            implementation = _simple_rule_impl,
            attrs = {},
        )

        def _sometransition_impl(settings, attr):
            _ignore = (settings, attr)
            return {"//command_line_option:platform_suffix": "transitioned"}

        _sometransition = transition(
            implementation = _sometransition_impl,
            inputs = [],
            outputs = ["//command_line_option:platform_suffix"],
        )
        rule_with_transition = rule(
            implementation = _simple_rule_impl,
            cfg = _sometransition,
        )
        
        """.trimIndent()
        )
        write(
            "test/BUILD",
            """
        load("//test:defs.bzl", "rule_with_transition", "simple_rule")

        simple_rule(name = "buildme")

        rule_with_transition(name = "buildme_with_transition")
        
        """.trimIndent()
        )
    }

    /**
     * Performs loading and analysis on the fixed rule `//test:buildme` with the given
     * build options (as they'd appear on the command line).
     */
    @Throws(java.lang.Exception::class)
    private fun analyzeTarget(vararg args: String?) {
        val params: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("build")
        // Ideally we'd directly use BlazeRuntimeWrapper, but it's explicitly documented as not
        // exercising the command dispatcher, which is exactly what we need here.
        params.addAll(runtimeWrapper.options)
        params.add("//test:buildme")
        params.add("--nobuild") // Execution phase isn't necessary to collect configurations.
        Collections.addAll<String?>(params, *args)
        dispatcher.exec(params, "my client", outErr)
    }

    /**
     * Performs loading and analysis on the fixed rule `//test:buildme` with the given
     * build options (as they'd appear on the command line).
     */
    @Throws(java.lang.Exception::class)
    private fun analyzeTargetWithTransition(vararg args: String?) {
        val params: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("build")
        // Ideally we'd directly use BlazeRuntimeWrapper, but it's explicitly documented as not
        // exercising the command dispatcher, which is exactly what we need here.
        params.addAll(runtimeWrapper.options)
        params.add("//test:buildme_with_transition")
        params.add("--nobuild") // Execution phase isn't necessary to collect configurations.
        Collections.addAll<String?>(params, *args)
        dispatcher.exec(params, "my client", outErr)
    }

    /**
     * Calls <cod>blaze config --output=json with the given flags and returns the raw output.
     * 
     * 
     * Should be called after [.analyzeTarget] so there are actual configs to read.
    </cod> */
    @Throws(java.lang.Exception::class)
    private fun callConfigCommand(vararg args: String?): RecordingOutErr {
        val params: MutableList<String?> = com.google.common.collect.Lists.newArrayList<String?>("config")
        params.add("--output=json")
        Collections.addAll<String?>(params, *args)
        val recordingOutErr: RecordingOutErr = RecordingOutErr()
        dispatcher.exec(params, "my client", recordingOutErr)
        return recordingOutErr
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun showConfigIds() {
        analyzeTarget()
        val fullJson: JsonObject? =
            com.google.gson.JsonParser.parseString(callConfigCommand().outAsLatin1()).getAsJsonObject()
        // Should be: target configuration, target configuration without test.
        Truth.assertThat(fullJson).isNotNull()
        Truth.assertThat(fullJson.has("configuration-IDs")).isTrue()
        Truth.assertThat(fullJson.get("configuration-IDs").getAsJsonArray().size()).isEqualTo(3)
    }

    private fun skipNoConfig(configHash: JsonElement): Boolean {
        try {
            return !Gson()
                .fromJson(
                    callConfigCommand(configHash.getAsString()).outAsLatin1(),
                    ConfigurationForOutput::class.java
                ).mnemonic
                .contains("-noconfig")
        } catch (e: java.lang.Exception) {
            Truth.assertWithMessage("Failed to retrieve %s: %s", configHash.getAsString(), e.message)
                .fail()
            return false
        }
    }

    /**
     * Calls the config command to return all config hashes currently available.
     * 
     * @param includeNoConfig if true, include the "noconfig" configuration (see [     ]. Else filter
     * it out.
     */
    @Throws(java.lang.Exception::class)
    private fun getConfigHashes(includeNoConfig: Boolean): com.google.common.collect.ImmutableList<String> {
        return com.google.common.collect.Streams.stream<JsonElement?>(
            com.google.gson.JsonParser.parseString(callConfigCommand().outAsLatin1())
                .getAsJsonObject()
                .get("configuration-IDs")
                .getAsJsonArray()
                .iterator()
        )
            .filter(if (includeNoConfig) com.google.common.base.Predicates.alwaysTrue<JsonElement?>() else java.util.function.Predicate { configHash: JsonElement? ->
                this.skipNoConfig(
                    configHash
                )
            })
            .map<String?> { c: JsonElement? -> c.getAsString() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun showSingleConfig() {
        analyzeTarget()
        // Find the first non-noconfig configuration (see NoConfigTransition). noconfig is a special
        // configuration that strips away most of its structure, so not a good candidate for this test.
        val configHash: String = getConfigHashes( /* includeNoConfig= */false).get(0)
        val config: ConfigurationForOutput =
            Gson()
                .fromJson(callConfigCommand(configHash).outAsLatin1(), ConfigurationForOutput::class.java)

        assertThat(config).isNotNull()
        // Verify config metadata:
        assertThat(config.configHash).isEqualTo(configHash)
        assertThat(config.skyKey)
            .isEqualTo(String.format("BuildConfigurationKey[%s]", configHash))
        // Verify the existence of a couple of expected fragments:
        assertThat(
            config.getFragments().stream()
                .map(
                    { fragment ->
                        Pair.of(
                            getBaseName(fragment.name),
                            getBaseNames(fragment.fragmentOptions)
                        )
                    })
                .collect(Collectors.toList())
        )
            .containsAtLeast(
                Pair.of("PlatformConfiguration", com.google.common.collect.ImmutableList.of<E?>("PlatformOptions")),
                Pair.of(
                    "TestConfiguration",
                    com.google.common.collect.ImmutableList.of<E?>("TestConfiguration\$TestOptions")
                )
            )
        // Verify the existence of a couple of expected fragment options:
        assertThat(
            config.getFragmentOptions().stream()
                .map({ fragment -> getBaseName(fragment.name) })
                .collect(Collectors.toList())
        )
            .containsAtLeast("PlatformOptions", "CoreOptions", "user-defined")
        // Verify the existence of a couple of expected option names:
        assertThat(
            config.getFragmentOptions().stream()
                .filter({ fragment -> fragment.name.endsWith("CoreOptions") })
                .flatMap({ fragment -> fragment.getOptions().keySet().stream() })
                .collect(Collectors.toList())
        )
            .containsAtLeast("run_under", "check_visibility", "stamp")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun showSingleConfigHashPrefix() {
        analyzeTarget()
        val configHash: String =
            com.google.gson.JsonParser.parseString(callConfigCommand().outAsLatin1())
                .getAsJsonObject()
                .get("configuration-IDs")
                .getAsJsonArray()
                .get(0)
                .getAsString()
        val hashPrefix: String = configHash.substring(0, configHash.length / 2)
        val config: ConfigurationForOutput =
            Gson()
                .fromJson(callConfigCommand(hashPrefix).outAsLatin1(), ConfigurationForOutput::class.java)
        assertThat(config).isNotNull()
        assertThat(config.configHash).startsWith(hashPrefix)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownHashPrefix() {
        analyzeTarget()
        val configHash: String =
            com.google.gson.JsonParser.parseString(callConfigCommand().outAsLatin1())
                .getAsJsonObject()
                .get("configuration-IDs")
                .getAsJsonArray()
                .get(0)
                .getAsString()
        // No valid hash has spaces.
        val hashPrefix = configHash.substring(0, configHash.length / 2) + " "
        com.google.common.truth.Subject.contains("No configuration found with ID prefix " + hashPrefix)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun showAllConfigs() {
        analyzeTarget()

        var numConfigs = 0
        for (configJson in com.google.gson.JsonParser.parseString(callConfigCommand("--dump_all").outAsLatin1())
            .getAsJsonArray()) {
            val config: ConfigurationForOutput? =
                Gson().fromJson<ConfigurationForOutput?>(configJson, ConfigurationForOutput::class.java)
            assertThat(config).isNotNull()
            numConfigs++
        }
        Truth.assertThat(numConfigs).isEqualTo(3) // Target + target w/o test + nonConfig.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compareConfigs() {
        // Do not trim test configuration for now to make 'finding' the configurations easier.
        analyzeTargetWithTransition("--platform_suffix=pure", "--notrim_test_configuration")
        val targetConfig1Hash: String = this.targetConfig.configHash
        val targetConfig2Hash: String? =
            getTargetConfig( /* excludedHashes= */com.google.common.collect.ImmutableSet.of<String?>(targetConfig1Hash)).configHash

        // Get their diff.
        val result: String? = callConfigCommand(targetConfig1Hash, targetConfig2Hash).outAsLatin1()
        val diff: ConfigurationDiffForOutput =
            Gson().fromJson<ConfigurationDiffForOutput>(result, ConfigurationDiffForOutput::class.java)
        assertThat(diff).isNotNull()
        assertThat(diff.configHash1).isEqualTo(targetConfig1Hash)
        assertThat(diff.configHash2).isEqualTo(targetConfig2Hash)
        val fragmentDiff: FragmentDiffForOutput? =
            com.google.common.collect.Iterables.getOnlyElement<FragmentDiffForOutput?>(diff.fragmentsDiff)
        assertThat(fragmentDiff.name).endsWith("CoreOptions")
        val optionDiff: MutableMap.MutableEntry<String?, Pair<String?, String?>?>? =
            com.google.common.collect.Iterators.getOnlyElement<T?>(
                fragmentDiff.optionsDiff.entrySet().stream()
                    .filter({ x -> !x.getKey().equals("affected by starlark transition") })
                    .iterator()
            )
        Truth.assertThat(optionDiff!!.key).isEqualTo("platform_suffix")
        // Convert from Pair<firstVal, secondVal> to an ImmutableList because the ordering of the
        // difference depends on which configuration comes first, which depends on the configuration
        // hash name, which we can't predict statically.
        Truth.assertThat(
            com.google.common.collect.ImmutableList.of<Any?>(
                optionDiff.value.first,
                optionDiff.value.second
            )
        )
            .containsExactly("pure", "transitioned")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compareConfigsHashPrefix() {
        // Do not trim test configuration for now to make 'finding' the configurations easier.
        analyzeTargetWithTransition("--platform_suffix=pure", "--notrim_test_configuration")
        val targetConfig1Hash: String = this.targetConfig.configHash
        val targetConfig2Hash: String =
            getTargetConfig( /* excludedHashes= */com.google.common.collect.ImmutableSet.of<String?>(targetConfig1Hash)).configHash

        val hashPrefix1: String = targetConfig1Hash.substring(0, targetConfig1Hash.length / 2)
        val hashPrefix2: String = targetConfig2Hash.substring(0, targetConfig2Hash.length / 2)

        val diff: ConfigurationDiffForOutput =
            Gson()
                .fromJson(
                    callConfigCommand(hashPrefix1, hashPrefix2).outAsLatin1(),
                    ConfigurationDiffForOutput::class.java
                )
        assertThat(diff).isNotNull()
        assertThat(diff.configHash1).startsWith(hashPrefix1)
        assertThat(diff.configHash2).startsWith(hashPrefix2)
    }

    @get:Throws(java.lang.Exception::class)
    private val targetConfig: ConfigurationForOutput
        get() = getTargetConfig(com.google.common.collect.ImmutableSet.of<String?>())

    @Throws(java.lang.Exception::class)
    private fun getTargetConfig(excludedHashes: com.google.common.collect.ImmutableSet<String?>): ConfigurationForOutput {
        // Find a target configuration hash.
        for (element in com.google.gson.JsonParser.parseString(callConfigCommand().outAsLatin1())
            .getAsJsonObject()
            .get("configuration-IDs")
            .getAsJsonArray()) {
            val configHash: String = element.getAsString()
            if (excludedHashes.contains(configHash)) {
                continue
            }
            val config: ConfigurationForOutput =
                Gson()
                    .fromJson(callConfigCommand(configHash).outAsLatin1(), ConfigurationForOutput::class.java)
            if (isTargetConfig(config)) {
                return config
            }
        }
        throw java.lang.AssertionError("Should have found config hash")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkFlagsInUserDefinedFragment() {
        write(
            "test/flagdef.bzl",
            """
        def _rule_impl(ctx):
            return []

        string_flag = rule(
            implementation = _rule_impl,
            build_setting = config.string(flag = True),
        )
        simple_rule = rule(
            implementation = _rule_impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        write(
            "custom_flags/BUILD",
            """
        load("//test:flagdef.bzl", "string_flag")

        string_flag(
            name = "my_flag",
            build_setting_default = "",
        )
        
        """.trimIndent()
        )

        analyzeTarget("--//custom_flags:my_flag=hello")

        var targetConfig: ConfigurationForOutput? = null
        val result: String = callConfigCommand("--dump_all").outAsLatin1()
        for (configJson in com.google.gson.JsonParser.parseString(result).getAsJsonArray()) {
            val config: ConfigurationForOutput =
                Gson().fromJson<ConfigurationForOutput>(configJson, ConfigurationForOutput::class.java)
            if (isTargetConfig(config)) {
                targetConfig = config
                break
            }
        }

        assertThat(targetConfig).isNotNull()
        Truth.assertThat(getOptionValue(targetConfig, "user-defined", "//custom_flags:my_flag"))
            .isEqualTo("hello")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defineFlagsIndividuallyListedInUserDefinedFragment() {
        analyzeTarget("--define", "a=1", "--define", "b=2")

        var targetConfig: ConfigurationForOutput? = null
        for (configJson in com.google.gson.JsonParser.parseString(callConfigCommand("--dump_all").outAsLatin1())
            .getAsJsonArray()) {
            val config: ConfigurationForOutput =
                Gson().fromJson<ConfigurationForOutput>(configJson, ConfigurationForOutput::class.java)
            if (isTargetConfig(config)) {
                targetConfig = config
                break
            }
        }

        assertThat(targetConfig).isNotNull()
        Truth.assertThat(getOptionValue(targetConfig, "user-defined", "--define:a")).isEqualTo("1")
        Truth.assertThat(getOptionValue(targetConfig, "user-defined", "--define:b")).isEqualTo("2")
        assertThat(
            targetConfig.getFragmentOptions().stream()
                .filter({ fragment -> fragment.name.endsWith("CoreOptions") })
                .flatMap({ fragment -> fragment.getOptions().keySet().stream() })
                .filter({ name -> name.equals("define") })
                .collect(Collectors.toList())
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun conflictingDefinesLastWins() {
        analyzeTarget("--define", "a=1", "--define", "a=2")

        var targetConfig: ConfigurationForOutput? = null
        for (configJson in com.google.gson.JsonParser.parseString(callConfigCommand("--dump_all").outAsLatin1())
            .getAsJsonArray()) {
            val config: ConfigurationForOutput =
                Gson().fromJson<ConfigurationForOutput>(configJson, ConfigurationForOutput::class.java)
            if (isTargetConfig(config)) {
                targetConfig = config
                break
            }
        }

        assertThat(targetConfig).isNotNull()
        Truth.assertThat(getOptionValue(targetConfig, "user-defined", "--define:a")).isEqualTo("2")
        assertThat(
            targetConfig.getFragmentOptions().stream()
                .filter({ fragment -> fragment.name.endsWith("CoreOptions") })
                .flatMap({ fragment -> fragment.getOptions().keySet().stream() })
                .filter({ name -> name.equals("define") })
                .collect(Collectors.toList())
        )
            .isEmpty()
    }

    companion object {
        /**
         * Returns the value of an option under a configuration's [FragmentOptions].
         * 
         * 
         * Throws [NoSuchElementException] if it can't be found.
         */
        private fun getOptionValue(
            config: ConfigurationForOutput, fragmentOptions: String?, optionName: String?
        ): String? {
            val ans: MutableList<String?> =
                config.getFragmentOptions().stream()
                    .filter({ fragment -> fragment.name.endsWith(fragmentOptions) })
                    .flatMap({ fragment -> fragment.getOptions().entrySet().stream() })
                    .filter({ setting -> setting.getKey().equals(optionName) })
                    .map({ java.util.Map.Entry.value })
                    .collect(Collectors.toList())
            if (ans.size > 1) {
                throw java.util.NoSuchElementException(
                    String.format(
                        "Multiple matches for fragment=%s, option=%s", fragmentOptions, optionName
                    )
                )
            } else if (ans.isEmpty()) {
                throw java.util.NoSuchElementException(
                    String.format("No matches for fragment=%s, option=%s", fragmentOptions, optionName)
                )
            }
            return ans.get(0)
        }

        private fun isTargetConfig(config: ConfigurationForOutput): Boolean {
            if (config.mnemonic.endsWith("-noconfig")) {
                return false
            }
            return !getOptionValue(config, "CoreOptions", "is exec configuration").toBoolean()
        }

        /** Converts `a.b.d` to `d`.  */
        private fun getBaseName(str: String): String {
            return str.substring(str.lastIndexOf(".") + 1)
        }

        /** Converts a list of `a.b.d` strings to `d` form.  */
        private fun getBaseNames(list: MutableList<String?>): MutableList<String?> {
            return list.stream().map<String?> { str: String? -> Companion.getBaseName(str!!) }
                .collect(Collectors.toList())
        }
    }
}
