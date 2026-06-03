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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.Spawn

/** Tests for [Scrubber].  */
@RunWith(JUnit4::class)
class ScrubberTest {
    @org.junit.Test
    fun noScrubbing() {
        val scrubber: Scrubber = Scrubber(Config.getDefaultInstance())

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNull()
    }

    @org.junit.Test
    fun matchExactMnemonic() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setMnemonic("Foo"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foobar"))).isNull()
    }

    @org.junit.Test
    fun matchUnionMnemonic() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setMnemonic("Foo|Bar"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Bar"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Baz"))).isNull()
    }

    @org.junit.Test
    fun matchWildcardMnemonic() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setMnemonic("Foo.*"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foobar"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Bar"))).isNull()
    }

    @org.junit.Test
    fun matchExactLabel() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setLabel("//foo:bar"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:barbaz", "Foo"))).isNull()
    }

    @org.junit.Test
    fun matchUnionLabel() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setLabel("//foo:bar|//spam:eggs"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//spam:eggs", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//quux:xyzzy", "Foo"))).isNull()
    }

    @org.junit.Test
    fun matchWildcardLabel() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setLabel("//foo:.*"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:baz", "Foo"))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//spam:eggs", "Foo"))).isNull()
    }

    @org.junit.Test
    fun matchExactKind() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setKind("java_library"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo", "java_library", false)))
            .isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:barbaz", "Foo", "java_test", false))).isNull()
    }

    @org.junit.Test
    fun matchUnionKind() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setKind("java_library|java_test"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo", "java_library", false)))
            .isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//spam:eggs", "Foo", "java_test", false)))
            .isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//quux:xyzzy", "Foo", "go_library", false))).isNull()
    }

    @org.junit.Test
    fun matchWildcardKind() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setKind("java_.*"))
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo", "java_library", false)))
            .isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//foo:baz", "Foo", "java_test", false))).isNotNull()
        assertThat(scrubber.forSpawn(createSpawn("//spam:eggs", "Foo", "go_library", false))).isNull()
    }

    @org.junit.Test
    fun rejectToolAction() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(
                                Config.Matcher.newBuilder().setLabel("//foo:bar").setMnemonic("Foo")
                            )
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(
            scrubber.forSpawn(createSpawn("//foo:bar", "Foo", "java_library",  /* forTool= */true))
        )
            .isNull()
    }

    @org.junit.Test
    fun acceptToolAction() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(
                                Config.Matcher.newBuilder()
                                    .setLabel("//foo:bar")
                                    .setMnemonic("Foo")
                                    .setMatchTools(true)
                            )
                    )
                    .build()
            )

        assertThat(scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))).isNotNull()
        assertThat(
            scrubber.forSpawn(createSpawn("//foo:bar", "Foo", "java_library",  /* forTool= */true))
        )
            .isNotNull()
    }

    @org.junit.Test
    fun noOmittedInputs() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(Config.newBuilder().addRules(Config.Rule.getDefaultInstance()).build())
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar"))).isFalse()
    }

    @org.junit.Test
    fun exactOmittedInput() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder().addOmittedInputs("foo/bar")
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar"))).isTrue()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar/baz"))).isFalse()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("bazel-out/foo/bar"))).isFalse()
    }

    @org.junit.Test
    fun wildcardOmittedInput() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder().addOmittedInputs("foo/bar.*")
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar"))).isTrue()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar/baz"))).isTrue()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("bazel-out/foo/bar"))).isFalse()
    }

    @org.junit.Test
    fun multipleOmittedInputs() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addOmittedInputs("foo/bar")
                                    .addOmittedInputs("spam/eggs")
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar"))).isTrue()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("spam/eggs"))).isTrue()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("foo/bar/baz"))).isFalse()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("bazel-out/foo/bar"))).isFalse()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("spam/eggs/bacon"))).isFalse()
        assertThat(spawnScrubber.shouldOmitInput(PathFragment.create("bazel-out/spam/eggs"))).isFalse()
    }

    @org.junit.Test
    fun simpleArgReplacement() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("foo")
                                            .setTarget("bar")
                                    )
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.transformArgument("foo")).isEqualTo("bar")
        assertThat(spawnScrubber.transformArgument("abcfooxyz")).isEqualTo("abcbarxyz")
        assertThat(spawnScrubber.transformArgument("bar")).isEqualTo("bar")
        assertThat(spawnScrubber.transformArgument("foofoo")).isEqualTo("barfoo")
    }

    @org.junit.Test
    fun anchoredArgReplacement() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("^foo$")
                                            .setTarget("bar")
                                    )
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.transformArgument("foo")).isEqualTo("bar")
        assertThat(spawnScrubber.transformArgument("abcfooxyz")).isEqualTo("abcfooxyz")
    }

    @org.junit.Test
    fun wildcardArgReplacement() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("foo[12]")
                                            .setTarget("bar")
                                    )
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.transformArgument("foo1")).isEqualTo("bar")
        assertThat(spawnScrubber.transformArgument("foo2")).isEqualTo("bar")
        assertThat(spawnScrubber.transformArgument("foo3")).isEqualTo("foo3")
    }

    @org.junit.Test
    fun multipleArgReplacements() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("foo")
                                            .setTarget("bar")
                                    )
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("spam")
                                            .setTarget("eggs")
                                    )
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.transformArgument("abcfoo123spamxyz")).isEqualTo("abcbar123eggsxyz")
        assertThat(spawnScrubber.transformArgument("abcfoo")).isEqualTo("abcbar")
        assertThat(spawnScrubber.transformArgument("abcspam")).isEqualTo("abceggs")
        assertThat(spawnScrubber.transformArgument("bareggs")).isEqualTo("bareggs")
    }

    @org.junit.Test
    fun withoutSalt() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(Config.newBuilder().addRules(Config.Rule.getDefaultInstance()).build())
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.getSalt()).isEmpty()
    }

    @org.junit.Test
    fun withSalt() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(Config.Transform.newBuilder().setSalt("NaCl"))
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.getSalt()).isEqualTo("NaCl")
    }

    @org.junit.Test
    fun orthogonalRules() {
        val scrubber: Scrubber =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setLabel("//foo:bar"))
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("foo")
                                            .setTarget("bar")
                                    )
                            )
                    )
                    .addRules(
                        Config.Rule.newBuilder()
                            .setMatcher(Config.Matcher.newBuilder().setLabel("//spam:eggs"))
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("spam")
                                            .setTarget("eggs")
                                    )
                            )
                    )
                    .build()
            )

        val spawnScrubberForFooBar: SpawnScrubber = scrubber.forSpawn(createSpawn("//foo:bar", "Foo"))
        assertThat(spawnScrubberForFooBar).isNotNull()
        assertThat(spawnScrubberForFooBar.transformArgument("foospam")).isEqualTo("barspam")

        val spawnScrubberForSpamEggs: SpawnScrubber = scrubber.forSpawn(createSpawn("//spam:eggs", "Spam"))
        assertThat(spawnScrubberForSpamEggs).isNotNull()
        assertThat(spawnScrubberForSpamEggs.transformArgument("foospam")).isEqualTo("fooeggs")
    }

    @org.junit.Test
    fun lastRuleWins() {
        val spawnScrubber: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Scrubber(
                Config.newBuilder()
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("foo")
                                            .setTarget("bar")
                                    )
                            )
                    )
                    .addRules(
                        Config.Rule.newBuilder()
                            .setTransform(
                                Config.Transform.newBuilder()
                                    .addArgReplacements(
                                        Config.Replacement.newBuilder()
                                            .setSource("spam")
                                            .setTarget("eggs")
                                    )
                            )
                    )
                    .build()
            )
                .forSpawn(createSpawn())

        assertThat(spawnScrubber.transformArgument("foospam")).isEqualTo("fooeggs")
    }

    companion object {
        private fun createSpawn(): Spawn {
            return createSpawn("//foo:bar", "Foo")
        }

        private fun createSpawn(label: String?, mnemonic: String?): Spawn {
            return createSpawn(label, mnemonic,  /* ruleKind= */"dummy-target-kind",  /* forTool= */false)
        }

        private fun createSpawn(
            label: String?, mnemonic: String?, ruleKind: String?, forTool: Boolean
        ): Spawn {
            return SpawnBuilder("cmd")
                .withOwnerLabel(label)
                .withMnemonic(mnemonic)
                .withOwnerRuleKind(ruleKind)
                .setBuiltForToolConfiguration(forTool)
                .build()
        }
    }
}
