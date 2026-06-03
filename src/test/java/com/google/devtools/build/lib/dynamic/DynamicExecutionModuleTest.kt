// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Tests for [com.google.devtools.build.lib.dynamic.DynamicExecutionModule].  */
@RunWith(JUnit4::class)
class DynamicExecutionModuleTest {
    private var module: DynamicExecutionModule? = null
    private var options: DynamicExecutionOptions? = null
    private var blazeRuntime: BlazeRuntime? = null

    @Before
    @Throws(IOException::class, AbruptExitException::class)
    fun setUp() {
        module = DynamicExecutionModule(Executors.newCachedThreadPool())
        options =
            com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions>(DynamicExecutionOptions::class.java)
        options.dynamicLocalStrategy =
            mutableListOf<MutableMap.MutableEntry<String?, MutableList<String?>?>?>() // default
        options.dynamicRemoteStrategy =
            mutableListOf<MutableMap.MutableEntry<String?, MutableList<String?>?>?>() // default
    }

    @org.junit.Test
    @Throws(AbruptExitException::class, OptionsParsingException::class)
    fun testGetLocalStrategies_getsDefaultWithNoOptions() {
        Truth.assertThat(module.getLocalStrategies(options,  /* sandboxingSupported= */true))
            .isEqualTo(parseStrategies("worker,sandboxed"))
        Truth.assertThat(module.getLocalStrategies(options,  /* sandboxingSupported= */false))
            .isEqualTo(parseStrategies("worker"))
    }

    @org.junit.Test
    @Throws(AbruptExitException::class, OptionsParsingException::class)
    fun testGetLocalStrategies_genericOptionOverridesFallbacks() {
        options.dynamicLocalStrategy = parseStrategiesToOptions("local,worker")
        Truth.assertThat(module.getLocalStrategies(options,  /* sandboxingSupported= */true))
            .isEqualTo(parseStrategies("local,worker"))
    }

    @org.junit.Test
    @Throws(AbruptExitException::class, OptionsParsingException::class)
    fun testGetLocalStrategies_specificOptionKeepsFallbacks() {
        options.dynamicLocalStrategy = parseStrategiesToOptions("Foo=local,worker")
        Truth.assertThat(module.getLocalStrategies(options,  /* sandboxingSupported= */true))
            .isEqualTo(parseStrategies("Foo=local,worker", "worker,sandboxed"))
    }

    @org.junit.Test
    @Throws(AbruptExitException::class, OptionsParsingException::class)
    fun testGetLocalStrategies_canMixSpecificsAndGenericOptions() {
        options.dynamicLocalStrategy = parseStrategiesToOptions("Foo=local,worker", "worker")
        Truth.assertThat(module.getLocalStrategies(options,  /* sandboxingSupported= */true))
            .isEqualTo(parseStrategies("Foo=local,worker", "worker"))
    }

    @org.junit.Test
    @Throws(IOException::class, AbruptExitException::class)
    fun canIgnoreFailure_simpleCases() {
        setupRuntime()
        val spawn: Spawn = SpawnBuilder().withOutput("output").build()
        val mockCommandEnvironment: CommandEnvironment =
            Mockito.mock<CommandEnvironment>(CommandEnvironment::class.java)
        val mockOptions: OptionsParsingResult = Mockito.mock<OptionsParsingResult>(OptionsParsingResult::class.java)
        Mockito.`when`<T?>(mockCommandEnvironment.getOptions()).thenReturn(mockOptions)
        val mockEventBus: com.google.common.eventbus.EventBus? =
            Mockito.mock<com.google.common.eventbus.EventBus?>(com.google.common.eventbus.EventBus::class.java)
        Mockito.`when`<T?>(mockCommandEnvironment.getEventBus()).thenReturn(mockEventBus)
        Mockito.`when`<T?>(mockCommandEnvironment.getBlazeWorkspace()).thenReturn(blazeRuntime.getWorkspace())
        val options: DynamicExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<DynamicExecutionOptions>(DynamicExecutionOptions::class.java)
        Mockito.`when`<DynamicExecutionOptions?>(
            mockOptions.getOptions<DynamicExecutionOptions?>(
                DynamicExecutionOptions::class.java
            )
        ).thenReturn(options)
        val context: ActionExecutionContext = Mockito.mock<ActionExecutionContext>(ActionExecutionContext::class.java)

        options.ignoreLocalSignals = com.google.common.collect.ImmutableSet.of<Int?>()
        module.beforeCommand(mockCommandEnvironment)
        Truth.assertThat(module.canIgnoreFailure(spawn, context, 130, "Failed", null, true)).isFalse()

        options.ignoreLocalSignals = com.google.common.collect.ImmutableSet.of<Int?>(9)
        module.beforeCommand(mockCommandEnvironment)
        Truth.assertThat(module.canIgnoreFailure(spawn, context, 130, "Failed", null, true)).isFalse()

        options.ignoreLocalSignals = com.google.common.collect.ImmutableSet.of<Int?>(2, 9)
        module.beforeCommand(mockCommandEnvironment)
        Truth.assertThat(module.canIgnoreFailure(spawn, context, 130, "Failed", null, false)).isFalse()
        Truth.assertThat(module.canIgnoreFailure(spawn, context, 0, "Failed", null, true)).isFalse()
        Truth.assertThat(module.canIgnoreFailure(spawn, context, 130, "Failed", null, true)).isTrue()
        Truth.assertThat(module.canIgnoreFailure(spawn, context, 137, "Failed", null, true)).isTrue()
    }

    @Throws(IOException::class, AbruptExitException::class)
    private fun setupRuntime() {
        val scratch: Scratch = Scratch()
        val execDir: Path = scratch.dir("/foo")
        val root: Root = Root.fromPath(execDir)
        val serverDirectories: ServerDirectories =
            ServerDirectories(
                scratch.dir("/installBase"),
                root.getRelative(OUTPUT_BASE),
                scratch.dir("/output-user")
            )
        blazeRuntime =
            Builder()
                .setFileSystem(scratch.getFileSystem())
                .setProductName(TestConstants.PRODUCT_NAME)
                .setServerDirectories(serverDirectories)
                .setStartupOptionsProvider(< T > mock < T ? > (OptionsParsingResult::class.java))
        .build()
        val binTools: BinTools? = BinTools.forUnitTesting(execDir, com.google.common.collect.ImmutableList.of<E?>())
        blazeRuntime.initWorkspace(
            BlazeDirectories(
                serverDirectories,
                scratch.dir(TestConstants.WORKSPACE_NAME),
                TestConstants.PRODUCT_NAME
            ),
            binTools
        )
    }

    companion object {
        private val OUTPUT_BASE: PathFragment? = PathFragment.create("blaze-out")

        @Throws(OptionsParsingException::class)
        private fun parseStrategiesToOptions(
            vararg strategies: String?
        ): MutableList<MutableMap.MutableEntry<String?, MutableList<String?>?>?> {
            val result = parseStrategies(*strategies)
            return java.util.ArrayList<MutableMap.MutableEntry<String?, MutableList<String?>?>?>(result.entries)
        }

        @Throws(OptionsParsingException::class)
        private fun parseStrategies(vararg strategies: String?): MutableMap<String?, MutableList<String?>?> {
            val result: MutableMap<String?, MutableList<String?>?> = LinkedHashMap<String?, MutableList<String?>?>()
            val converter: StringToStringListConverter = StringToStringListConverter()
            for (s in strategies) {
                val converted: MutableMap.MutableEntry<String?, MutableList<String?>?> = converter.convert(s)
                // Have to avoid using Immutable* to allow overwriting elements.
                result.put(converted.key, java.util.ArrayList<String?>(converted.value))
            }
            return result
        }
    }
}
