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

import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.DisallowValues

/** Test InvocationPolicies with the DisallowValues operation.  */
@RunWith(JUnit4::class)
class InvocationPolicyDisallowValuesTest : InvocationPolicyEnforcerTestBase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesAllowsValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(DISALLOWED_VALUE_1)
            .addDisallowedValues(DISALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + UNFILTERED_VALUE)

        // Option should be "baz" as specified by the user.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(UNFILTERED_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Still "baz" since "baz" is allowed by the policy.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(UNFILTERED_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesDisallowsValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(DISALLOWED_VALUE_1)
            .addDisallowedValues(DISALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + DISALLOWED_VALUE_1)

        // Option should be "foo" as specified by the user.
        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(DISALLOWED_VALUE_1)

        // expected, since foo is disallowed.
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
    fun testDisallowValuesDisallowsMultipleValues() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(DISALLOWED_VALUE_1)
            .addDisallowedValues(DISALLOWED_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse(
            "--test_multiple_string=" + UNFILTERED_VALUE,
            "--test_multiple_string=" + DISALLOWED_VALUE_2
        )

        // Option should be "baz" and "bar" as specified by the user.
        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(UNFILTERED_VALUE, DISALLOWED_VALUE_2)
            .inOrder()

        // expected, since bar is disallowed.
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
    fun testDisallowValuesSetsNewValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(DISALLOWED_VALUE_1)
            .setNewValue(UNFILTERED_VALUE)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + DISALLOWED_VALUE_1)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(DISALLOWED_VALUE_1)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Should now be "baz" because the policy forces disallowed values to "baz"
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(UNFILTERED_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesSetsDefaultValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(DISALLOWED_VALUE_1)
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + DISALLOWED_VALUE_1)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(DISALLOWED_VALUE_1)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesSetsDefaultValueForRepeatableFlag() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(DISALLOWED_VALUE_1)
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_multiple_string=" + DISALLOWED_VALUE_1)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString()).containsExactly(DISALLOWED_VALUE_1)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        // Default for repeatable flags is always empty.
        Truth.assertThat(testOptions.getTestMultipleString()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesRaisesErrorIfDefaultIsDisallowedAndSetsUseDefault() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

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
        Truth.assertThat(e).hasMessageThat().contains("but also specifies to use the default value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesSetsNewValueWhenDefaultIsDisallowed() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder()
            .addDisallowedValues(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
            .setNewValue(UNFILTERED_VALUE)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        // Option should be the default since the use didn't specify a value.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Should now be "baz" because the policy set the new default to "baz"
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(UNFILTERED_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowValuesDisallowsFlagDefaultButNoPolicyDefault() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getDisallowValuesBuilder() // No new default is set
            .addDisallowedValues(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        // Option should be the default since the use didn't specify a value.
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
    fun testDisallowValuesDisallowsListConverterFlag() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_list_converters")
            .getDisallowValuesBuilder()
            .addDisallowedValues("a")

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
                "Flag value 'a' for option '--test_list_converters' is not allowed by invocation "
                        + "policy"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowValuesWithNullDefault_DoesNotConfuseNullForDefault() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string_null_by_default")
            .setDisallowValues(
                DisallowValues.newBuilder()
                    .addDisallowedValues("null")
                    .setUseDefault(UseDefault.getDefaultInstance())
            )
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        // Check the value before invocation policy enforcement.
        parser.parse("--test_string_null_by_default=null")
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isEqualTo("null")

        // Check the value afterwards.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestStringNullByDefault()).isNull()
    }

    companion object {
        // Useful constants
        const val BUILD_COMMAND: String = "build"
        const val DISALLOWED_VALUE_1: String = "foo"
        const val DISALLOWED_VALUE_2: String = "bar"
        const val UNFILTERED_VALUE: String = "baz"
    }
}
