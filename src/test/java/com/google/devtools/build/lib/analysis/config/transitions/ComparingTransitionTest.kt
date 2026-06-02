// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** [ComparingTransition] tests.  */
@RunWith(JUnit4::class)
class ComparingTransitionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sameOutputs() {
        val trans1: PatchTransition = PatchTransition { options, eventHandler -> options.underlying() }
        val trans2: PatchTransition = PatchTransition { options, eventHandler -> options.underlying() }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("unique fragments in trans1 mode: none")
        Truth.assertThat(msg).contains("unique fragments in trans2 mode: none")
        Truth.assertThat(msg).contains("total option differences: 0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentNativeFlag() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java).setStampBinaries(true)
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java).setStampBinaries(false)
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 1")
        Truth.assertThat(msg).contains("CoreOptions stamp: trans1 mode=true, trans2 mode=false")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentDefineValues() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions
                    .get(CoreOptions::class.java)
                    .setCommandLineBuildVariables(
                        com.google.common.collect.ImmutableList.of<E?>(
                            java.util.Map.entry<K?, V?>(
                                "myvar",
                                "1"
                            )
                        )
                    )
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions
                    .get(CoreOptions::class.java)
                    .setCommandLineBuildVariables(
                        com.google.common.collect.ImmutableList.of<E?>(
                            java.util.Map.entry<K?, V?>(
                                "myvar",
                                "2"
                            )
                        )
                    )
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 1")
        Truth.assertThat(msg).contains("user-defined define myvar (index 0): trans1 mode=1, trans2 mode=2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentDefineOrder() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions
                    .get(CoreOptions::class.java)
                    .setCommandLineBuildVariables(
                        com.google.common.collect.ImmutableList.of<E?>(
                            java.util.Map.entry<K?, V?>("var1", "1"),
                            java.util.Map.entry<K?, V?>("var2", "2")
                        )
                    )
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions
                    .get(CoreOptions::class.java)
                    .setCommandLineBuildVariables(
                        com.google.common.collect.ImmutableList.of<E?>(
                            java.util.Map.entry<K?, V?>("var2", "2"),
                            java.util.Map.entry<K?, V?>("var1", "1")
                        )
                    )
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 4")
        Truth.assertThat(msg).contains("only in trans1 mode: --user-defined define var1 (index 0)=1")
        Truth.assertThat(msg).contains("only in trans1 mode: --user-defined define var2 (index 1)=2")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined define var2 (index 0)=2")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined define var1 (index 1)=1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentFeaturesValues() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setDefaultFeatures(com.google.common.collect.ImmutableList.of<E?>("a"))
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setDefaultFeatures(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 1")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined feature b (index 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentFeaturesOrder() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setDefaultFeatures(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setDefaultFeatures(com.google.common.collect.ImmutableList.of<E?>("b", "a"))
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 4")
        Truth.assertThat(msg).contains("only in trans1 mode: --user-defined feature a (index 0)")
        Truth.assertThat(msg).contains("only in trans1 mode: --user-defined feature b (index 1)")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined feature b (index 0)")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined feature a (index 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentHostFeaturesValues() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setHostFeatures(com.google.common.collect.ImmutableList.of<E?>("a"))
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setHostFeatures(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 1")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined host feature b (index 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentHostFeaturesOrder() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setHostFeatures(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                toOptions
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                val toOptions: BuildOptions = options.underlying().clone()
                toOptions.get(CoreOptions::class.java)
                    .setHostFeatures(com.google.common.collect.ImmutableList.of<E?>("b", "a"))
                toOptions
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 4")
        Truth.assertThat(msg).contains("only in trans1 mode: --user-defined host feature a (index 0)")
        Truth.assertThat(msg).contains("only in trans1 mode: --user-defined host feature b (index 1)")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined host feature b (index 0)")
        Truth.assertThat(msg).contains("only in trans2 mode: --user-defined host feature a (index 1)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentStarlarkFlagValues() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                options.underlying().toBuilder()
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//foo"), "1")
                    .build()
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                options.underlying().toBuilder()
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//foo"), "2")
                    .build()
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 1")
        Truth.assertThat(msg).contains("--user-defined  //foo:foo (index 0): trans1 mode=1, trans2 mode=2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkFlagOrderAutomaticallySorted() {
        val trans1: PatchTransition =
            PatchTransition { options, eventHandler ->
                options.underlying().toBuilder()
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//a"), "a")
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//b"), "b")
                    .build()
            }
        val trans2: PatchTransition =
            PatchTransition { options, eventHandler ->
                options.underlying().toBuilder()
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//b"), "b")
                    .addStarlarkOption(Label.parseCanonicalUnchecked("//a"), "a")
                    .build()
            }
        val fromOptions: BuildOptionsView =
            BuildOptionsView(
                targetConfig.getOptions(), targetConfig.getOptions().getFragmentClasses()
            )

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ComparingTransition(trans1, "trans1", trans2, "trans2", { b -> true })
                .patch(fromOptions, reporter)
        val msg: String? =
            com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.events.Event?>(
                eventCollector.filtered(com.google.devtools.build.lib.events.EventKind.INFO)
            ).getMessage()

        Truth.assertThat(msg).contains("total option differences: 0")
    }
}
