// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options.processor.optiontestsources

import OptionFilters.OptionEffectTag
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass

/**
 * This class should contain all of the types with DEFAULT_CONVERTERS, and each converter should be
 * found without generating compilation errors.
 */
@OptionsClass
abstract class AllDefaultConverters : OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "boolean_option",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract var booleanOption: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "double_option",
        defaultValue = "42.73",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract var doubleOption: Double

    @get:com.google.devtools.common.options.Option(
        name = "int_option",
        defaultValue = "42",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract var intOption: Int

    @get:com.google.devtools.common.options.Option(
        name = "long_option",
        defaultValue = "-5000000000000",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract var longOption: Long

    @get:com.google.devtools.common.options.Option(
        name = "string_option",
        defaultValue = "strings are strings are strings are strings",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract var stringOption: String?

    @get:com.google.devtools.common.options.Option(
        name = "tri_state_option",
        defaultValue = "auto",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract val triStateOption: com.google.devtools.common.options.TriState?

    abstract fun setTriStateOption(value: com.google.devtools.common.options.TriState?)

    @get:com.google.devtools.common.options.Option(
        name = "duration_option",
        defaultValue = "3600s",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract val durationOption: java.time.Duration?

    abstract fun setDurationOption(value: java.time.Duration?)

    @get:com.google.devtools.common.options.Option(
        name = "void_option",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS]
    )
    abstract val voidOption: java.lang.Void?

    abstract fun setVoidOption(value: java.lang.Void?)
}
