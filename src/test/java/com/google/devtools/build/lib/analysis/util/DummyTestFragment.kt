// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.devtools.build.lib.analysis.config.BuildOptions
import com.google.devtools.common.options.Converter
import com.google.devtools.common.options.Option
import java.util.*

/**
 * Expose a set of options that can be added to [BuildViewTestCase] and friends in order to
 * force configuration changes without materially affecting the build.
 * 
 * 
 * Previously, supposed 'no-op' options like --test_arg were used; however, this interferes with
 * --trim_test_configuration.
 * 
 * 
 * Note that, for [BuildViewTestCase], these can be 'enables' by overriding [ ] and using [ ] for DummyTestFragment.class.
 */
@RequiresOptions(options = [DummyTestFragment.DummyTestOptions::class])
class DummyTestFragment(buildOptions: BuildOptions?) : Fragment() {
    /** Flags that exhibit a variety of flag behaviors.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:Option(
            name = "nullable_option",
            converter = EmptyToNullLabelConverter::class,
            defaultValue = "",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "An option that is sometimes set to null."
        )
        abstract val nullable: Label?

        @get:Option(
            name = "foo",
            defaultValue = "",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "A regular string-typed option"
        )
        abstract var foo: String?

        @get:Option(
            name = "internal foo",
            defaultValue = "",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            metadataTags = [OptionMetadataTag.INTERNAL],
            help = "A string-typed option that cannot be set on the commandline"
        )
        abstract val internalFoo: String?

        @get:Option(
            name = "bar",
            defaultValue = "",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "A regular string-typed option"
        )
        abstract val bar: String?

        @get:Option(
            name = "bazes",
            defaultValue = "null",
            allowMultiple = true,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "A regular string-typed option"
        )
        abstract val bazes: MutableList<String?>?

        @get:Option(
            name = "bool",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "A regular bool-typed option"
        )
        abstract val bool: Boolean

        @get:Option(
            name = "unreadable_by_starlark",
            defaultValue = "anything",
            converter = UnreadableStringBoxConverter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "This cannot be used as an input to a Starlark transition"
        )
        abstract val unreadableByStarlark: UnreadableStringBox?

        @get:Option(
            name = "allow_multiple_with_env_var_converter",
            defaultValue = "null",
            allowMultiple = true,
            converter = EnvVar.Converter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "allowMultiple flag with EnvVar converter"
        )
        abstract val allowMultipleWithEnvVarConverter: MutableList<EnvVar>?

        @get:Option(
            name = "allow_multiple_with_list_converter",
            defaultValue = "null",
            allowMultiple = true,
            converter = CommaSeparatedOptionListConverter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            help = "allowMultiple flag where the converter returns a list"
        )
        abstract val allowMultipleWithListConverter: MutableList<String?>?

        @AutoCodec
        @kotlin.jvm.JvmRecord
        data class UnreadableStringBox(val value: String?) {
            init {
                Objects.requireNonNull<String?>(value, "value")
            }

            companion object {
                fun create(value: String?): UnreadableStringBox {
                    return UnreadableStringBox(value)
                }
            }
        }

        class UnreadableStringBoxConverter : Converter<UnreadableStringBox?> {
            @Throws(OptionsParsingException::class)
            override fun convert(input: String?, conversionContext: Any?): UnreadableStringBox {
                return UnreadableStringBox.Companion.create(input)
            }

            override fun getTypeDescription(): String {
                return "a string that is not readable by Starlark"
            }
        }
    }
}
