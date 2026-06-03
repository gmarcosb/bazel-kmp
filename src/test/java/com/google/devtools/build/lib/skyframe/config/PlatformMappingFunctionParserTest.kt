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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Unit tests for [PlatformMappingFunction].  */
@RunWith(JUnit4::class)
class PlatformMappingFunctionParserTest : AnalysisTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParse() {
        val mappings: Mappings =
            parse(
                "platforms:",
                "  //platforms:one",
                "    --cpu=one",
                "  //platforms:two",
                "    --cpu=two",
                "flags:",
                "  --cpu=one",
                "    //platforms:one",
                "  --cpu=two",
                "    //platforms:two"
            )

        assertThat(mappings.platformsToFlags.keySet()).containsExactly(PLATFORM1, PLATFORM2)
        assertThat(mappings.platformsToFlags.get(PLATFORM1).parsingResult().canonicalize())
            .containsExactly("--cpu=one")
        assertThat(mappings.platformsToFlags.get(PLATFORM2).parsingResult().canonicalize())
            .containsExactly("--cpu=two")

        assertThat(mappings.flagsToPlatforms.keySet())
            .containsExactly(createFlags("--cpu=one"), createFlags("--cpu=two"))
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=one"))).isEqualTo(PLATFORM1)
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=two"))).isEqualTo(PLATFORM2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseWithRepoMapping() {
        val repoMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "foo",
                    RepositoryName.MAIN,
                    "dep",
                    RepositoryName.create("dep+1.0")
                ),
                RepositoryName.MAIN
            )
        val mappings: Mappings =
            parse(
                repoMapping,
                "platforms:",
                "  @foo//platforms:one",
                "    --cpu=one",
                "  @dep//platforms:two",
                "    --cpu=two",
                "flags:",
                "  --cpu=one",
                "    @foo//platforms:one",
                "  --cpu=two",
                "    @dep//platforms:two"
            )

        assertThat(mappings.platformsToFlags.keySet()).containsExactly(PLATFORM1, EXTERNAL_PLATFORM)
        assertThat(mappings.platformsToFlags.get(PLATFORM1).parsingResult().canonicalize())
            .containsExactly("--cpu=one")
        assertThat(mappings.platformsToFlags.get(EXTERNAL_PLATFORM).parsingResult().canonicalize())
            .containsExactly("--cpu=two")

        assertThat(mappings.flagsToPlatforms.keySet())
            .containsExactly(
                createFlags(repoMapping, "--cpu=one"), createFlags(repoMapping, "--cpu=two")
            )
        assertThat(mappings.flagsToPlatforms.get(createFlags(repoMapping, "--cpu=one")))
            .isEqualTo(PLATFORM1)
        assertThat(mappings.flagsToPlatforms.get(createFlags(repoMapping, "--cpu=two")))
            .isEqualTo(EXTERNAL_PLATFORM)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseComment() {
        val mappings: Mappings =
            parse(
                "# A mapping file!",
                "platforms:",
                "  # comment1",
                "  //platforms:one",
                "# comment2",
                "    --cpu=one",
                "  //platforms:two",
                "    --cpu=two",
                "flags:",
                "# another comment",
                "  --cpu=one",
                "    //platforms:one",
                "  --cpu=two",
                "    //platforms:two"
            )

        assertThat(mappings.platformsToFlags.keySet()).containsExactly(PLATFORM1, PLATFORM2)
        assertThat(mappings.platformsToFlags.get(PLATFORM1).parsingResult().canonicalize())
            .containsExactly("--cpu=one")
        assertThat(mappings.platformsToFlags.get(PLATFORM2).parsingResult().canonicalize())
            .containsExactly("--cpu=two")

        assertThat(mappings.flagsToPlatforms.keySet())
            .containsExactly(createFlags("--cpu=one"), createFlags("--cpu=two"))
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=one"))).isEqualTo(PLATFORM1)
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=two"))).isEqualTo(PLATFORM2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseWhitespace() {
        val mappings: Mappings =
            parse(
                "",
                "platforms:",
                "  ",
                "  //platforms:one",
                "",
                "    --cpu=one",
                "    //platforms:two    ",
                "      --cpu=two ",
                "flags:",
                "           ",
                "",
                "--cpu=one",
                "  //platforms:one",
                "  --cpu=two",
                "  //platforms:two"
            )

        assertThat(mappings.platformsToFlags.keySet()).containsExactly(PLATFORM1, PLATFORM2)
        assertThat(mappings.platformsToFlags.get(PLATFORM1).parsingResult().canonicalize())
            .containsExactly("--cpu=one")
        assertThat(mappings.platformsToFlags.get(PLATFORM2).parsingResult().canonicalize())
            .containsExactly("--cpu=two")

        assertThat(mappings.flagsToPlatforms.keySet())
            .containsExactly(createFlags("--cpu=one"), createFlags("--cpu=two"))
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=one"))).isEqualTo(PLATFORM1)
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=two"))).isEqualTo(PLATFORM2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseMultipleFlagsInPlatform() {
        val mappings: Mappings =
            parse(
                "platforms:",
                "  //platforms:one",
                "    --cpu=one",
                "    --compilation_mode=dbg",
                "  //platforms:two",
                "    --cpu=two"
            )

        assertThat(mappings.platformsToFlags.keySet()).containsExactly(PLATFORM1, PLATFORM2)
        assertThat(mappings.platformsToFlags.get(PLATFORM1).parsingResult().canonicalize())
            .containsExactly("--cpu=one", "--compilation_mode=dbg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseMultipleFlagsInFlags() {
        val mappings: Mappings =
            parse(
                "flags:",
                "  --compilation_mode=dbg",
                "  --cpu=one",
                "    //platforms:one",
                "  --cpu=two",
                "    //platforms:two"
            )

        assertThat(mappings.flagsToPlatforms.keySet())
            .containsExactly(
                createFlags("--compilation_mode=dbg", "--cpu=one"), createFlags("--cpu=two")
            )
        assertThat(mappings.flagsToPlatforms.get(createFlags("--compilation_mode=dbg", "--cpu=one")))
            .isEqualTo(PLATFORM1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseOnlyPlatforms() {
        val mappings: Mappings =
            parse(
                "platforms:",  // Force line break
                "  //platforms:one",  // Force line break
                "    --cpu=one" // Force line break
            )

        assertThat(mappings.platformsToFlags.keySet()).containsExactly(PLATFORM1)
        assertThat(mappings.platformsToFlags.get(PLATFORM1).parsingResult().canonicalize())
            .containsExactly("--cpu=one")
        assertThat(mappings.flagsToPlatforms).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseOnlyFlags() {
        val mappings: Mappings =
            parse(
                "flags:",  // Force line break
                "  --cpu=one",  // Force line break
                "    //platforms:one" // Force line break
            )

        assertThat(mappings.flagsToPlatforms.keySet()).containsExactly(createFlags("--cpu=one"))
        assertThat(mappings.flagsToPlatforms.get(createFlags("--cpu=one"))).isEqualTo(PLATFORM1)
        assertThat(mappings.platformsToFlags).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseEmpty() {
        val mappings: Mappings = parse()

        assertThat(mappings.flagsToPlatforms).isEmpty()
        assertThat(mappings.platformsToFlags).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseEmptySections() {
        val mappings: Mappings = parse("platforms:", "flags:")

        assertThat(mappings.flagsToPlatforms).isEmpty()
        assertThat(mappings.platformsToFlags).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseCommentOnly() {
        val mappings: Mappings = parse("#No mappings")

        assertThat(mappings.flagsToPlatforms).isEmpty()
        assertThat(mappings.platformsToFlags).isEmpty()
    }

    @org.junit.Test
    fun testParseExtraPlatformInFlags() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "flags:",  // Force line break
                        "  --cpu=one",  // Force line break
                        "    //platforms:one",  // Force line break
                        "    //platforms:two" // Force line break
                    )
                })

        assertThat(exception).hasMessageThat().contains("//platforms:two")
    }

    @org.junit.Test
    fun testParsePlatformWithoutFlags() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "platforms:",  // Force line break
                        "  //platforms:one" // Force line break
                    )
                })

        assertThat(exception).hasMessageThat().contains("end of file")
    }

    @org.junit.Test
    fun testParseFlagsWithoutPlatform() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "flags:",  // Force line break
                        "  --cpu=one" // Force line break
                    )
                })

        assertThat(exception).hasMessageThat().contains("end of file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParseCommentEndOfFile() {
        val mappings: Mappings =
            parse(
                "platforms:",  // Force line break
                "  //platforms:one",  // Force line break
                "    --cpu=one",  // Force line break
                "# No more mappings" // Force line break
            )

        assertThat(mappings.platformsToFlags).isNotEmpty()
    }

    @org.junit.Test
    fun testParseUnknownSection() {
        var exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "platform:",  // Force line break
                        "  //platforms:one",  // Force line break
                        "    --cpu=one" // Force line break
                    )
                })

        assertThat(exception).hasMessageThat().contains("platform:")

        exception =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "platforms:",
                        "  //platforms:one",
                        "    --cpu=one",
                        "flag:",
                        "  --cpu=one",
                        "    //platforms:one"
                    )
                })

        assertThat(exception).hasMessageThat().contains("platform")
    }

    @org.junit.Test
    fun testParsePlatformsInvalidPlatformLabel() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "platforms:",  // Force line break
                        "  @@@",  // Force line break
                        "    --cpu=one"
                    )
                })

        assertThat(exception).hasMessageThat().contains("@@@")
    }

    @org.junit.Test
    fun testParseFlagsInvalidPlatformLabel() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "flags:",  // Force line break
                        "  --cpu=one",  // Force line break
                        "    @@@"
                    )
                })

        assertThat(exception).hasMessageThat().contains("@@@")
    }

    @org.junit.Test
    fun testParsePlatformsInvalidFlag() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "platforms:",  // Force line break
                        "  //platforms:one",  // Force line break
                        "    -cpu=one"
                    )
                })

        assertThat(exception).hasMessageThat().contains("-cpu")
    }

    @org.junit.Test
    fun testParseFlagsInvalidFlag() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "flags:",  // Force line break
                        "  -cpu=one",  // Force line breakPlatformMappingFunction
                        "    //platforms:one"
                    )
                })

        assertThat(exception).hasMessageThat().contains("-cpu")
    }

    @org.junit.Test
    fun testParsePlatformsDuplicatePlatform() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "platforms:",  // Force line break
                        "  //platforms:one",  // Force line break
                        "    --cpu=one",  // Force line break
                        "  //platforms:one",  // Force line break
                        "    --cpu=two"
                    )
                })

        assertThat(exception).hasMessageThat().contains("duplicate")
    }

    @org.junit.Test
    fun testParseFlagsDuplicateFlags() {
        val exception: PlatformMappingParsingException? =
            org.junit.Assert.assertThrows<T?>(
                PlatformMappingParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parse(
                        "flags:",  // Force line break
                        "  --compilation_mode=dbg",  // Force line break
                        "  --cpu=one",  // Force line break:242
                        "    //platforms:one",  // Force line break
                        "  --compilation_mode=dbg",  // Force line break
                        "  --cpu=one",  // Force line break
                        "    //platforms:two"
                    )
                })

        assertThat(exception).hasMessageThat().contains("duplicate")
    }

    @Throws(OptionsParsingException::class)
    private fun createFlags(vararg nativeFlags: String?): ParsedFlagsValue? {
        return createFlags(RepositoryMapping.EMPTY, nativeFlags)
    }

    @Throws(OptionsParsingException::class)
    private fun createFlags(mainRepoMapping: RepositoryMapping?, vararg nativeFlags: String?): ParsedFlagsValue {
        val flags: NativeAndStarlarkFlags? =
            NativeAndStarlarkFlags.builder()
                .nativeFlags(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (nativeFlags))
                .optionsClasses(ruleClassProvider.getFragmentRegistry().getOptionsClasses())
                .repoMapping(mainRepoMapping)
                .build()
        return ParsedFlagsValue.parseAndCreate(flags)
    }

    @Throws(PlatformMappingParsingException::class, java.lang.InterruptedException::class)
    private fun parse(vararg lines: String?): Mappings {
        return parse(RepositoryMapping.EMPTY, lines)
    }

    @Throws(java.lang.InterruptedException::class, PlatformMappingParsingException::class)
    private fun parse(mainRepoMapping: RepositoryMapping?, vararg lines: String?): Mappings {
        val key: Key =
            com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionParserTest.Key.Companion.create(
                mainRepoMapping,
                com.google.common.collect.ImmutableList.copyOf<String?>(lines)
            )
        try {
            // Must re-enable analysis for Skyframe functions that create configured targets.
            skyframeExecutor.getSkyframeBuildView().enableAnalysis(true)
            val evalResult: EvaluationResult<Value?> =
                SkyframeExecutorTestUtils.evaluate<T?>(
                    skyframeExecutor, key,  /* keepGoing= */false, reporter
                )
            if (evalResult.hasError()) {
                val errorInfo: ErrorInfo = evalResult.getError(key)
                throw errorInfo.getException() as PlatformMappingParsingException?
            }
            return evalResult.get(key).mappings()
        } finally {
            skyframeExecutor.getSkyframeBuildView().enableAnalysis(false)
        }
    }

    @AutoCodec
    internal class Key(mainRepoMapping: RepositoryMapping?, lines: com.google.common.collect.ImmutableList<String?>?) :
        SkyKey {
        private val mainRepoMapping: RepositoryMapping?
        private val lines: com.google.common.collect.ImmutableList<String?>?

        init {
            this.mainRepoMapping = mainRepoMapping
            this.lines = lines
        }

        fun mainRepoMapping(): RepositoryMapping? {
            return mainRepoMapping
        }

        fun lines(): com.google.common.collect.ImmutableList<String?>? {
            return lines
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(mainRepoMapping, lines)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Key) {
                return false
            }
            return mainRepoMapping == o.mainRepoMapping
                    && lines == o.lines
        }

        public override fun functionName(): SkyFunctionName? {
            return SKYFUNCTION_NAME
        }

        companion object {
            fun create(
                mainRepoMapping: RepositoryMapping?,
                lines: com.google.common.collect.ImmutableList<String?>?
            ): Key {
                return com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionParserTest.Key(
                    mainRepoMapping,
                    lines
                )
            }
        }
    }

    internal class Value(mappings: Mappings?) : SkyValue {
        private val mappings: Mappings?

        init {
            this.mappings = mappings
        }

        fun mappings(): Mappings? {
            return mappings
        }

        companion object {
            fun create(mappings: Mappings?): Value {
                return com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionParserTest.Value(mappings)
            }
        }
    }

    private class ParseMappingsFunction : SkyFunction {
        @Throws(
            java.lang.InterruptedException::class,
            com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionParserTest.ParseMappingsFunction.EvalException::class
        )
        public override fun compute(skyKey: SkyKey, env: Environment?): Value? {
            val key = skyKey.argument() as Key
            try {
                val mappings: Mappings? =
                    PlatformMappingFunction.parse(
                        env, key.lines(), RepoContext.of(RepositoryName.MAIN, key.mainRepoMapping())
                    )
                if (mappings == null) {
                    return null
                }
                return com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionParserTest.Value.Companion.create(
                    mappings
                )
            } catch (e: PlatformMappingParsingException) {
                throw com.google.devtools.build.lib.skyframe.config.PlatformMappingFunctionParserTest.ParseMappingsFunction.EvalException(
                    e
                )
            }
        }

        private class EvalException(cause: java.lang.Exception?) : SkyFunctionException(cause, Transience.PERSISTENT)
    }

    private class CustomAnalysisMock :
        com.google.devtools.build.lib.analysis.util.AnalysisMock.Delegate(AnalysisMock.get()) {
        public override fun getSkyFunctions(
            directories: BlazeDirectories
        ): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
            return com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(super.getSkyFunctions(directories))
                .put(SKYFUNCTION_NAME, ParseMappingsFunction())
                .buildOrThrow()
        }
    }

    val analysisMock: AnalysisMock
        get() = CustomAnalysisMock()

    companion object {
        private val PLATFORM1: Label? = Label.parseCanonicalUnchecked("//platforms:one")
        private val PLATFORM2: Label? = Label.parseCanonicalUnchecked("//platforms:two")
        private val EXTERNAL_PLATFORM: Label? = Label.parseCanonicalUnchecked("@dep+1.0//platforms:two")

        private val SKYFUNCTION_NAME: SkyFunctionName? = SkyFunctionName.createHermetic("PARSE_MAPPINGS")
    }
}
