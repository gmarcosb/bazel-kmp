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

import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.AllowValues

/** Test InvocationPolicies with the AllowValues operation.  */
@RunWith(JUnit4::class)
class InvocationPolicyAllowValuesTest : InvocationPolicyEnforcerTestBase() {
    /**
     * Tests that AllowValues works in the normal case where the value the user specified is allowed
     * by the policy.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesAllowsValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getAllowValuesBuilder()
            .addAllowedValues(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(ALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + ALLOWED_VALUE_1)

        // Option should be "foo" as specified by the user.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(ALLOWED_VALUE_1)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Still "foo" since "foo" is allowed by the policy.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(ALLOWED_VALUE_1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesDisallowsValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getAllowValuesBuilder() // no foo!
            .addAllowedValues(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
            .addAllowedValues(ALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + ALLOWED_VALUE_1)

        // Option should be "foo" as specified by the user.
        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(ALLOWED_VALUE_1)

        // Should throw because "foo" is not allowed.
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable {
                enforcer.enforce(
                    parser,
                    BUILD_COMMAND,
                    com.google.common.collect.ImmutableList.builder<E?>()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesDisallowsMultipleValues() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getAllowValuesBuilder()
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(ALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse(
            "--test_multiple_string=" + UNFILTERED_VALUE, "--test_multiple_string=" + ALLOWED_VALUE_2
        )

        // Option should be "baz" and "bar" as specified by the user.
        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(UNFILTERED_VALUE, ALLOWED_VALUE_2)
            .inOrder()

        // expected, since baz is not allowed.
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable {
                enforcer.enforce(
                    parser,
                    BUILD_COMMAND,
                    com.google.common.collect.ImmutableList.builder<E?>()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesSetsNewValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getAllowValuesBuilder()
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(ALLOWED_VALUE_2)
            .setNewValue(ALLOWED_VALUE_1)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + UNFILTERED_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(UNFILTERED_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(ALLOWED_VALUE_1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesSetsDefaultValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getAllowValuesBuilder()
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + UNFILTERED_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(UNFILTERED_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesSetsDefaultValueForRepeatableFlag() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getAllowValuesBuilder()
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(ALLOWED_VALUE_2)
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse(
            "--test_multiple_string=" + ALLOWED_VALUE_1, "--test_multiple_string=" + UNFILTERED_VALUE
        )

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(ALLOWED_VALUE_1, UNFILTERED_VALUE)
            .inOrder()

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        // Default value for repeatable flags is always empty.
        Truth.assertThat(testOptions.getTestMultipleString()).isEmpty()
    }

    /**
     * Tests that AllowValues sets its default value when the user doesn't provide a value and the
     * flag's default value is disallowed.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesSetsNewDefaultWhenFlagDefaultIsDisallowed() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getAllowValuesBuilder() // default value from flag's definition is not allowed
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(ALLOWED_VALUE_2)
            .setNewValue("new default")

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        // Option should be its default
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Flag's value should be the default value from the policy.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo("new default")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesDisallowsFlagDefaultButNoPolicyDefault() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getAllowValuesBuilder() // default value from flag's definition is not allowed, and no alternate default
            // is given.
            .addAllowedValues(ALLOWED_VALUE_1)
            .addAllowedValues(ALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        // Option should be its default
        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)

        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable {
                enforcer.enforce(
                    parser,
                    BUILD_COMMAND,
                    com.google.common.collect.ImmutableList.builder<E?>()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesDisallowsListConverterFlagValues() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_list_converters")
            .getAllowValuesBuilder()
            .addAllowedValues("a")

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_list_converters=a,b,c")

        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestListConverters()).isEqualTo(mutableListOf<String?>("a", "b", "c"))

        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    enforcer.enforce(
                        parser,
                        BUILD_COMMAND,
                        com.google.common.collect.ImmutableList.builder<E?>()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Flag value 'b' for option '--test_list_converters' is not allowed by invocation "
                        + "policy"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesWithNullDefault_AcceptedValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string_null_by_default")
            .setAllowValues(
                AllowValues.newBuilder()
                    .addAllowedValues("a")
                    .setUseDefault(UseDefault.getDefaultInstance())
            )
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        // Check the value before invocation policy enforcement.
        parser.parse("--test_string_null_by_default=a")
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isEqualTo("a")

        // Check the value afterwards.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isEqualTo("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesWithNullDefault_UsesNullDefaultToOverrideUnacceptedValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string_null_by_default")
            .setAllowValues(
                AllowValues.newBuilder()
                    .addAllowedValues("a")
                    .setUseDefault(UseDefault.getDefaultInstance())
            )
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        // Check the value before invocation policy enforcement.
        parser.parse("--test_string_null_by_default=b")
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isEqualTo("b")

        // Check the value afterwards.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesWithNullDefault_AllowsUnsetValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string_null_by_default")
            .setAllowValues(
                AllowValues.newBuilder()
                    .addAllowedValues("a")
                    .setUseDefault(UseDefault.getDefaultInstance())
            )
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        // Check the value before invocation policy enforcement.
        parser.parse()
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isNull()

        // Check the value afterwards.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isNull()
    }

    companion object {
        // Useful constants
        const val BUILD_COMMAND: String = "build"
        const val ALLOWED_VALUE_1: String = "foo"
        const val ALLOWED_VALUE_2: String = "bar"
        const val UNFILTERED_VALUE: String = "baz"
    }
}
