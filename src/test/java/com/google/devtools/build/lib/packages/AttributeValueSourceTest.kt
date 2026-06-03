// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Test class for [AttributeValueSource].
 */
@RunWith(JUnit4::class)
class AttributeValueSourceTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidateStarlarkName() {
        // Success means "no exception is being thrown".
        AttributeValueSource.COMPUTED_DEFAULT.validateStarlarkName("_name")
        AttributeValueSource.LATE_BOUND.validateStarlarkName("_name")
        AttributeValueSource.MATERIALIZER.validateStarlarkName("_name")
        AttributeValueSource.DIRECT.validateStarlarkName("_name")
        AttributeValueSource.DIRECT.validateStarlarkName("name")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidateStarlarkName_emptyName() {
        for (source in AttributeValueSource.values()) {
            assertNameIsNotValid(source, "", "Attribute name must not be empty.")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidateStarlarkName_missingPrefix() {
        val msg =
            ("When an attribute value is a function, the attribute must be private "
                    + "(i.e. start with '_'). Found 'my_name'")
        assertNameIsNotValid(AttributeValueSource.COMPUTED_DEFAULT, "my_name", msg)
        assertNameIsNotValid(AttributeValueSource.LATE_BOUND, "my_name", msg)
        assertNameIsNotValid(AttributeValueSource.MATERIALIZER, "my_name", msg)
    }

    @Throws(java.lang.Exception::class)
    private fun assertNameIsNotValid(
        source: AttributeValueSource, name: String?, expectedExceptionMessage: String?
    ) {
        val ex: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { source.validateStarlarkName(name) })
        Truth.assertThat(ex).hasMessageThat().isEqualTo(expectedExceptionMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConvertToNativeName() {
        assertConvertsToCorrectNativeName(AttributeValueSource.COMPUTED_DEFAULT, "_name", "\$name")
        assertConvertsToCorrectNativeName(AttributeValueSource.LATE_BOUND, "_name", ":name")
        assertConvertsToCorrectNativeName(AttributeValueSource.MATERIALIZER, "_name", ":name")
        assertConvertsToCorrectNativeName(AttributeValueSource.DIRECT, "_name", "\$name")
        assertConvertsToCorrectNativeName(AttributeValueSource.DIRECT, "name", "name")
    }

    @Throws(java.lang.Exception::class)
    private fun assertConvertsToCorrectNativeName(
        source: AttributeValueSource, starlarkName: String?, expectedNativeName: String?
    ) {
        assertThat(source.convertToNativeName(starlarkName)).isEqualTo(expectedNativeName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConvertToNativeName_invalidName() {
        assertTranslationFails(AttributeValueSource.COMPUTED_DEFAULT, "name")
        assertTranslationFails(AttributeValueSource.LATE_BOUND, "name")
        assertTranslationFails(AttributeValueSource.MATERIALIZER, "name")
    }

    @Throws(java.lang.Exception::class)
    private fun assertTranslationFails(source: AttributeValueSource, invalidName: String?) {
        val ex: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { source.convertToNativeName(invalidName) })
        Truth.assertThat(ex)
            .hasMessageThat()
            .isEqualTo(
                String.format(
                    "When an attribute value is a function, the attribute must be private "
                            + "(i.e. start with '_'). Found '%s'",
                    invalidName
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderGetValueSource() {
        assertBuilderHasCorrectSource(COMPUTED_DEFAULT_BUILDER, AttributeValueSource.COMPUTED_DEFAULT)
        assertBuilderHasCorrectSource(LATE_BOUND_BUILDER, AttributeValueSource.LATE_BOUND)
        assertBuilderHasCorrectSource(DIRECT_BUILDER, AttributeValueSource.DIRECT)
    }

    @Throws(java.lang.Exception::class)
    private fun assertBuilderHasCorrectSource(
        builder: Attribute.Builder<*>, expectedSource: AttributeValueSource?
    ) {
        assertThat(builder.getValueSource()).isEqualTo(expectedSource)
    }

    companion object {
        private val COMPUTED_DEFAULT_BUILDER: Attribute.Builder<*> = attr("x", STRING)
            .value(
                object : ComputedDefault() {
                    public override fun getDefault(rule: AttributeMap?): Any? {
                        return null
                    }
                })

        private val LATE_BOUND_BUILDER: Attribute.Builder<*> = attr(":x", STRING).value(LateBoundDefault.alwaysNull())

        private val DIRECT_BUILDER: Attribute.Builder<*> = attr("x", STRING).value("value")
    }
}
