// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.AttributeValueGetter

/**
 * Tests for [ImplicitOutputsFunction].
 */
@RunWith(JUnit4::class)
class ImplicitOutputsFunctionTest {
    @Throws(java.lang.Exception::class)
    private fun assertPlaceholderCollection(
        template: String?, expectedTemplate: String?, vararg expectedPlaceholders: String?
    ) {
        val actualPlaceholders: MutableList<String?> = java.util.ArrayList<String?>()
        assertThat(
            ImplicitOutputsFunction.createPlaceholderSubstitutionFormatString(
                template, actualPlaceholders
            )
        )
            .isEqualTo(expectedTemplate)
        Truth.assertThat(actualPlaceholders)
            .containsExactlyElementsIn(java.util.Arrays.asList<String?>(*expectedPlaceholders))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPlaceholder() {
        assertPlaceholderCollection("foo", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJustPlaceholder() {
        assertPlaceholderCollection("%{foo}", "%s", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrefixedPlaceholder() {
        assertPlaceholderCollection("foo%{bar}", "foo%s", "bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuffixedPlaceholder() {
        assertPlaceholderCollection("%{foo}bar", "%sbar", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplePlaceholdersPrefixed() {
        assertPlaceholderCollection("foo%{bar}baz%{qux}", "foo%sbaz%s", "bar", "qux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplePlaceholdersSuffixed() {
        assertPlaceholderCollection("%{foo}bar%{baz}qux", "%sbar%squx", "foo", "baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTightlyPackedPlaceholders() {
        assertPlaceholderCollection("%{foo}%{bar}%{baz}", "%s%s%s", "foo", "bar", "baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompletePlaceholder() {
        assertPlaceholderCollection("%{foo", "%%{foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompleteAndIncompletePlaceholder() {
        assertPlaceholderCollection("%{foo}%{bar", "%s%%{bar", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlaceholderLooksLikeNestedIncompletePlaceholder() {
        assertPlaceholderCollection("%{%{foo", "%%{%%{foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlaceholderLooksLikeNestedPlaceholder() {
        assertPlaceholderCollection("%{%{foo}", "%s", "%{foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscapesJustPercentSign() {
        assertPlaceholderCollection("%", "%%")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscapesPrintfPlaceholder() {
        assertPlaceholderCollection("%{x}%s%{y}", "%s%%s%s", "x", "y")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEscapesPercentSign() {
        assertPlaceholderCollection("foo%{bar}%baz", "foo%s%%baz", "bar")
    }

    @Throws(java.lang.Exception::class)
    private fun assertPlaceholderSubtitution(
        template: String?,
        attrValues: AttributeValueGetter?,
        expectedSubstitutions: Array<String?>,
        expectedFoundPlaceholders: Array<String?>?
    ) {
        // Directly call into ParsedTemplate in order to access the attribute names.
        val parsedTemplate: ImplicitOutputsFunction.ParsedTemplate =
            ImplicitOutputsFunction.ParsedTemplate.parse(template)

        assertThat(parsedTemplate.attributeNames())
            .containsExactlyElementsIn(java.util.Arrays.< T > asList < T ? > (expectedFoundPlaceholders))
            .inOrder()

        // Test the actual substitution code.
        val substitutions: MutableList<String?>? =
            ImplicitOutputsFunction.substitutePlaceholderIntoTemplate(template, null, attrValues)
        Truth.assertThat(substitutions)
            .containsExactlyElementsIn(java.util.Arrays.asList<String?>(*expectedSubstitutions))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleScalarElementSubstitution() {
        assertPlaceholderSubtitution(
            "%{x}",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x",
                    com.google.common.collect.ImmutableList.of<String?>("a")
                )
            ),
            arrayOf<String>("a"),
            arrayOf<String>("x")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleVectorElementSubstitution() {
        assertPlaceholderSubtitution(
            "%{x}",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x",
                    com.google.common.collect.ImmutableList.of<String?>("a", "b", "c")
                )
            ),
            arrayOf<String>("a", "b", "c"),
            arrayOf<String>("x")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleElementsSubstitution() {
        assertPlaceholderSubtitution(
            "%{x}-%{y}-%{z}",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x", com.google.common.collect.ImmutableList.of<String?>("foo", "bar", "baz"),
                    "y", com.google.common.collect.ImmutableList.of<String?>("meow"),
                    "z", com.google.common.collect.ImmutableList.of<String?>("1", "2")
                )
            ),
            arrayOf<String>(
                "foo-meow-1", "foo-meow-2", "bar-meow-1", "bar-meow-2", "baz-meow-1", "baz-meow-2"
            ),
            arrayOf<String>("x", "y", "z")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyElementSubstitution() {
        assertPlaceholderSubtitution(
            "a-%{x}",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x",
                    com.google.common.collect.ImmutableList.of<String?>()
                )
            ),
            arrayOfNulls<String>(0),
            arrayOf<String>("x")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSamePlaceholderMultipleTimes() {
        assertPlaceholderSubtitution(
            "%{x}-%{y}-%{x}",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x",
                    com.google.common.collect.ImmutableList.of<String?>("a", "b"),
                    "y",
                    com.google.common.collect.ImmutableList.of<String?>("1", "2")
                )
            ),
            arrayOf<String>("a-1-a", "a-1-b", "a-2-a", "a-2-b", "b-1-a", "b-1-b", "b-2-a", "b-2-b"),
            arrayOf<String>("x", "y", "x")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatingPlaceholderValue() {
        assertPlaceholderSubtitution(
            "%{x}",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x",
                    com.google.common.collect.ImmutableList.of<String?>("a", "a")
                )
            ),
            arrayOf<String>("a"),
            arrayOf<String>("x")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompletePlaceholderTreatedAsText() {
        assertPlaceholderSubtitution(
            "%{x}-%{y-%{z",
            attrs(
                com.google.common.collect.ImmutableMap.of<String?, com.google.common.collect.ImmutableList<String?>?>(
                    "x",
                    com.google.common.collect.ImmutableList.of<String?>("a", "b")
                )
            ),
            arrayOf<String>("a-%{y-%{z", "b-%{y-%{z"),
            arrayOf<String>("x")
        )
    }

    companion object {
        private fun attrs(
            values: MutableMap<String?, out MutableCollection<String?>?>
        ): AttributeValueGetter {
            return object : AttributeValueGetter() {
                public override fun get(ignored: AttributeMap?, attr: String?): MutableSet<String?> {
                    return LinkedHashSet<String?>(com.google.common.base.Preconditions.checkNotNull(values.get(attr)))
                }
            }
        }
    }
}
