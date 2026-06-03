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
package com.google.devtools.build.lib.packages.semantics

import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec
import org.junit.Test
import java.util.Arrays
import kotlin.Exception
import kotlin.String
import kotlin.toString

// TODO(b/173631499): We really should just delete this test entirely. However, it does catch the
// case of flipping a flag default but forgetting to update its string from "-foo" to "+foo", so
// make sure we have coverage for that.
/**
 * Tests for the flow of flags from [BuildLanguageOptions] to [StarlarkSemantics], and
 * to and from `StarlarkSemantics`' serialized representation.
 * 
 * 
 * When adding a new option, it is easy to make a transposition error or a copy/paste error.
 * These tests guard against such errors. The following possible bugs are considered:
 * 
 * 
 *  * If a new semantics option is stored in `StarlarkSemantics` by [       ][BuildLanguageOptions.toStarlarkSemantics] but has no associated command-line flag in
 * BuildLanguageOptions, or vice versa, then the programmer will either be unable to implement
 * its behavior, or unable to test it from the command line and add user documentation. We
 * hope that the programmer notices this on their own.
 *  * To catch a copy/paste error where the wrong field's data is threaded through `toStarlarkSemantics()` or `deserialize(...)`, we repeatedly generate matching random
 * instances of the input and expected output objects.
 *  * The [.checkDefaultsMatch] test ensures that there is no divergence between the
 * default values of the two classes.
 *  * There is no test coverage for failing to update the non-generated webpage documentation. So
 * don't forget that!
 * 
 */
@RunWith(JUnit4::class)
class ConsistencyTest {
    /**
     * Checks that a randomly generated [BuildLanguageOptions] object can be converted to a
     * [StarlarkSemantics] object with the same field values.
     */
    @Test
    @Throws(Exception::class)
    fun optionsToSemantics() {
        for (i in 0..<NUM_RANDOM_TRIALS) {
            val seed = i.toLong()
            val options: BuildLanguageOptions = buildRandomOptions(Random(seed))
            val semantics: StarlarkSemantics = buildRandomSemantics(Random(seed))
            val semanticsFromOptions: StarlarkSemantics = options.toStarlarkSemantics()
            assertThat(semanticsFromOptions).isEqualTo(semantics)
        }
    }

    /**
     * Checks that a randomly generated [StarlarkSemantics] object can be serialized and
     * deserialized to an equivalent object.
     */
    @Test
    @Throws(Exception::class)
    fun serializationRoundTrip() {
        val codec: DynamicCodec = DynamicCodec(buildRandomSemantics(Random(2)).getClass())
        for (i in 0..<NUM_RANDOM_TRIALS) {
            val semantics: StarlarkSemantics = buildRandomSemantics(Random(i.toLong()))
            val deserialized: StarlarkSemantics? =
                RoundTripping.fromBytes(
                    ImmutableDeserializationContext(),
                    codec,
                    RoundTripping.toBytes(
                        ObjectCodecs().getSerializationContextForTesting(), codec, semantics
                    )
                ) as StarlarkSemantics?
            assertThat(deserialized).isEqualTo(semantics)
        }
    }

    @Test
    fun checkDefaultsMatch() {
        val defaultOptions: BuildLanguageOptions = Options.getDefaults(BuildLanguageOptions::class.java)
        val defaultSemantics: StarlarkSemantics? =
            StarlarkSemantics.DEFAULT.toBuilder() // This flag must be false in Starlark, but true in Bazel by default.
                .setBool(StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS, true)
                .build()
        val semanticsFromOptions: StarlarkSemantics = defaultOptions.toStarlarkSemantics()
        assertThat(semanticsFromOptions).isEqualTo(defaultSemantics)
    }

    @Test
    fun canGetBuilderFromInstance() {
        val original: StarlarkSemantics = StarlarkSemantics.DEFAULT
        val flag = "-test"
        assertThat(original.getBool(flag)).isFalse()
        val modified: StarlarkSemantics = original.toBuilder().setBool(flag, true).build()
        assertThat(modified.getBool(flag)).isTrue()
    }

    companion object {
        private const val NUM_RANDOM_TRIALS = 10

        /**
         * Constructs a [BuildLanguageOptions] object with random fields. Must access `rand`
         * using the same sequence of operations (for the same fields) as [.buildRandomSemantics].
         */
        @Throws(Exception::class)
        private fun buildRandomOptions(rand: Random): BuildLanguageOptions {
            return parseOptions( // <== Add new options here in alphabetic order ==>
                "--experimental_disable_external_package=" + rand.nextBoolean(),
                "--experimental_sibling_repository_layout=" + rand.nextBoolean(),
                "--experimental_builtins_bzl_path=" + rand.nextDouble(),
                "--experimental_builtins_dummy=" + rand.nextBoolean(),
                "--experimental_bzl_visibility=" + rand.nextBoolean(),
                "--experimental_enable_android_migration_apis=" + rand.nextBoolean(),
                "--experimental_single_package_toolchain_binding=" + rand.nextBoolean(),
                "--experimental_isolated_extension_usages=" + rand.nextBoolean(),
                "--incompatible_no_implicit_watch_label=" + rand.nextBoolean(),
                "--experimental_google_legacy_api=" + rand.nextBoolean(),
                "--experimental_platforms_api=" + rand.nextBoolean(),
                "--incompatible_allow_tags_propagation=" + rand.nextBoolean(),  // flag, Java names differ
                "--experimental_cc_shared_library=" + rand.nextBoolean(),
                "--experimental_repo_remote_exec=" + rand.nextBoolean(),
                "--experimental_dormant_deps=" + rand.nextBoolean(),
                "--force_starlark_stack_trace=" + rand.nextBoolean(),
                "--incompatible_always_check_depset_elements=" + rand.nextBoolean(),
                "--incompatible_disallow_empty_glob=" + rand.nextBoolean(),
                "--incompatible_do_not_split_linking_cmdline=" + rand.nextBoolean(),
                "--incompatible_enable_deprecated_label_apis=" + rand.nextBoolean(),
                "--incompatible_enforce_starlark_utf8="
                        + BuildLanguageOptions.Utf8EnforcementMode.values()[rand.nextInt(BuildLanguageOptions.Utf8EnforcementMode.values().size)]
                    .toString()
                    .lowercase(),
                "--incompatible_locations_prefers_executable=" + rand.nextBoolean(),
                "--incompatible_no_attr_license=" + rand.nextBoolean(),
                "--incompatible_no_implicit_file_export=" + rand.nextBoolean(),
                "--incompatible_no_rule_outputs_param=" + rand.nextBoolean(),
                "--incompatible_run_shell_command_string=" + rand.nextBoolean(),
                "--incompatible_unambiguous_label_stringification=" + rand.nextBoolean(),
                "--incompatible_resolve_select_keys_eagerly=" + rand.nextBoolean(),
                "--internal_starlark_flag_test_canary=" + rand.nextBoolean(),
                "--internal_starlark_utf_8_byte_strings=" + rand.nextBoolean(),
                "--max_computation_steps=" + rand.nextLong()
            )
        }

        /**
         * Constructs a [StarlarkSemantics] object with random fields. Must access `rand`
         * using the same sequence of operations (for the same fields) as [.buildRandomOptions].
         */
        private fun buildRandomSemantics(rand: Random): StarlarkSemantics {
            return StarlarkSemantics.builder() // <== Add new options here in alphabetic order ==>
                .setBool(BuildLanguageOptions.EXPERIMENTAL_DISABLE_EXTERNAL_PACKAGE, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT, rand.nextBoolean())
                .set(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH, rand.nextDouble().toString())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_DUMMY, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_BZL_VISIBILITY, rand.nextBoolean())
                .setBool(
                    BuildLanguageOptions.EXPERIMENTAL_ENABLE_ANDROID_MIGRATION_APIS, rand.nextBoolean()
                )
                .setBool(
                    BuildLanguageOptions.EXPERIMENTAL_SINGLE_PACKAGE_TOOLCHAIN_BINDING, rand.nextBoolean()
                )
                .setBool(BuildLanguageOptions.EXPERIMENTAL_ISOLATED_EXTENSION_USAGES, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_PLATFORMS_API, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_ALLOW_TAGS_PROPAGATION, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_CC_SHARED_LIBRARY, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC, rand.nextBoolean())
                .setBool(BuildLanguageOptions.EXPERIMENTAL_DORMANT_DEPS, rand.nextBoolean())
                .setBool(StarlarkSemantics.FORCE_STARLARK_STACK_TRACE, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_ALWAYS_CHECK_DEPSET_ELEMENTS, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_DISALLOW_EMPTY_GLOB, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_DO_NOT_SPLIT_LINKING_CMDLINE, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS, rand.nextBoolean())
                .set(
                    BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8,
                    BuildLanguageOptions.Utf8EnforcementMode.values()[rand.nextInt(BuildLanguageOptions.Utf8EnforcementMode.values().size)]
                )
                .setBool(BuildLanguageOptions.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_ATTR_LICENSE, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM, rand.nextBoolean())
                .setBool(BuildLanguageOptions.INCOMPATIBLE_RUN_SHELL_COMMAND_STRING, rand.nextBoolean())
                .setBool(
                    BuildLanguageOptions.INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION, rand.nextBoolean()
                )
                .setBool(BuildLanguageOptions.INCOMPATIBLE_RESOLVE_SELECT_KEYS_EAGERLY, rand.nextBoolean())
                .setBool(StarlarkSemantics.PRINT_TEST_MARKER, rand.nextBoolean())
                .setBool(StarlarkSemantics.INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS, rand.nextBoolean())
                .set(BuildLanguageOptions.MAX_COMPUTATION_STEPS, rand.nextLong())
                .build()
        }

        @Throws(Exception::class)
        private fun parseOptions(vararg args: String?): BuildLanguageOptions {
            val parser: OptionsParser =
                OptionsParser.builder()
                    .optionsClasses(BuildLanguageOptions::class.java)
                    .allowResidue(false)
                    .build()
            parser.parse(Arrays.< T > asList < T ? > (args))
            return parser.getOptions(BuildLanguageOptions::class.java)
        }
    }
}
