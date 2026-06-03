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
package com.google.devtools.common.options

import OptionFilters.OptionEffectTag
import com.google.devtools.common.options.InvocationPolicyEnforcerTestBase.ToListConverter
import com.google.devtools.common.options.OptionDocumentationCategory
import com.google.devtools.common.options.OptionEffectTag
import com.google.devtools.common.options.OptionsBase
import com.google.devtools.common.options.OptionsClass

/** Options for testing.  */
@OptionsClass
abstract class TestOptions : OptionsBase() {
    @get:Option(
        name = "test_string",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT,
        help = "a string-valued option to test simple option operations"
    )
    abstract val testString: String?

    @get:Option(
        name = "test_string_null_by_default",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        help = "a string-valued option that has the special string 'null' as its default."
    )
    abstract val testStringNullByDefault: String?

    @get:Option(
        name = "test_multiple_string",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        allowMultiple = true,
        help = "a repeatable string-valued flag with its own unhelpful help text"
    )
    abstract val testMultipleString: MutableList<String?>?

    @get:Option(
        name = "test_list_converters",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        allowMultiple = true,
        converter = ToListConverter::class,
        help = ("a repeatable flag that accepts lists, but doesn't want to have lists of lists "
                + "as a final type")
    )
    abstract val testListConverters: MutableList<String?>?

    @get:Option(
        name = "test_expansion",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        expansion = ["--noexpanded_a", "--expanded_b=false", "--expanded_c", "42", "--expanded_d", "bar"
        ],
        help = "this expands to an alphabet soup."
    )
    abstract val testExpansion: java.lang.Void?

    @get:Option(
        name = "test_recursive_expansion_top_level",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        expansion = ["--test_recursive_expansion_middle1", "--test_recursive_expansion_middle2"
        ],
        help = "Lets the children do all the work."
    )
    abstract val testRecursiveExpansionTopLevel: java.lang.Void?

    @get:Option(
        name = "test_recursive_expansion_middle1",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        expansion = ["--expanded_a=false", "--expanded_c=56"
        ]
    )
    abstract val testRecursiveExpansionMiddle1: java.lang.Void?

    @get:Option(
        name = "test_recursive_expansion_middle2",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        expansion = ["--expanded_b=false", "--expanded_d=baz"
        ]
    )
    abstract val testRecursiveExpansionMiddle2: java.lang.Void?

    @get:Option(
        name = "expanded_a",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        defaultValue = "true",
        help = "A boolean flag with unknown effect to test tagless usage text."
    )
    abstract val expandedA: Boolean

    @get:Option(
        name = "expanded_b",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "true"
    )
    abstract val expandedB: Boolean

    @get:Option(
        name = "expanded_c",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "12",
        help = "an int-value'd flag used to test expansion logic"
    )
    abstract val expandedC: Int

    @get:Option(
        name = "expanded_d",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "foo"
    )
    abstract val expandedD: String?

    @get:Option(
        name = "test_expansion_to_repeatable",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = "null",
        expansion = ["--test_multiple_string=expandedFirstValue", "--test_multiple_string=expandedSecondValue"
        ],
        help = "Go forth and multiply, they said."
    )
    abstract val testExpansionToRepeatable: java.lang.Void?

    @get:Option(
        name = "test_implicit_requirement",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = com.google.devtools.common.options.TestOptions.Companion.TEST_IMPLICIT_REQUIREMENT_DEFAULT,
        implicitRequirements = ["--implicit_requirement_a=" + com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_REQUIRED],
        help = "this option really needs that other one, isolation of purpose has failed."
    )
    abstract val testImplicitRequirement: String?

    @get:Option(
        name = "implicit_requirement_a",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_DEFAULT
    )
    abstract val implicitRequirementA: String?

    @get:Option(
        name = "test_recursive_implicit_requirement",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        defaultValue = com.google.devtools.common.options.TestOptions.Companion.TEST_RECURSIVE_IMPLICIT_REQUIREMENT_DEFAULT,
        implicitRequirements = ["--test_implicit_requirement=" + com.google.devtools.common.options.TestOptions.Companion.TEST_IMPLICIT_REQUIREMENT_REQUIRED]
    )
    abstract val testRecursiveImplicitRequirement: String?

    @get:Option(
        name = "test_deprecated",
        defaultValue = "default",
        deprecationWarning = "Flag for testing deprecation behavior.",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP]
    )
    abstract val testDeprecated: String?

    @get:Option(
        name = "test_new_and_old_name",
        oldName = "test_old_name",
        defaultValue = "default",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP],
        help = "A test option with both a new name and an old name."
    )
    abstract val testNewAndOldName: String?

    @get:Option(
        name = "markdown_in_help",
        defaultValue = "default",
        help = """
          normal
          `code span`
          *emphasis*
          **strong emphasis**
          [inline link](/url (title))
          [reference link][ref]
          [shorthand reference link]
          [`complex` shorthand reference link]
          hard line\
          break
          ```
          code block
          ```
          - unordered
          - list
          1. ordered
          2. list

          paragraph 1

          paragraph 2

          `<HTML> "syntax" 'within' &codeblocks&`

          [ref]: /url (title)
          [shorthand reference link]: /url (title)
          [`complex` shorthand reference link]: /url (title)
          
          """.trimIndent(),
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.NO_OP]
    )
    abstract val markdownInHelp: String?

    companion object {
        /*
   * Basic types
   */
        const val TEST_STRING_DEFAULT: String = "test string default"

        /*
   * Expansion flags
   */
        const val EXPANDED_A_TEST_EXPANSION: Boolean = false
        const val EXPANDED_B_TEST_EXPANSION: Boolean = false
        const val EXPANDED_C_TEST_EXPANSION: Int = 42
        const val EXPANDED_D_TEST_EXPANSION: String = "bar"

        const val EXPANDED_A_TEST_RECURSIVE_EXPANSION: Boolean = false
        const val EXPANDED_B_TEST_RECURSIVE_EXPANSION: Boolean = false
        const val EXPANDED_C_TEST_RECURSIVE_EXPANSION: Int = 56
        const val EXPANDED_D_TEST_RECURSIVE_EXPANSION: String = "baz"

        const val EXPANDED_A_DEFAULT: Boolean = true

        const val EXPANDED_B_DEFAULT: Boolean = true

        const val EXPANDED_C_DEFAULT: Int = 12

        const val EXPANDED_D_DEFAULT: String = "foo"

        /*
   * Expansion into repeatable flags.
   */
        const val EXPANDED_MULTIPLE_1: String = "expandedFirstValue"
        const val EXPANDED_MULTIPLE_2: String = "expandedSecondValue"

        /*
   * Implicit requirement flags
   */
        const val TEST_IMPLICIT_REQUIREMENT_DEFAULT: String = "direct implicit"
        const val IMPLICIT_REQUIREMENT_A_REQUIRED: String = "implicit requirement, required"

        const val IMPLICIT_REQUIREMENT_A_DEFAULT: String = "implicit requirement, unrequired"

        const val TEST_RECURSIVE_IMPLICIT_REQUIREMENT_DEFAULT: String = "recursive implicit"
        const val TEST_IMPLICIT_REQUIREMENT_REQUIRED: String = "intermediate, required"
    }
}
