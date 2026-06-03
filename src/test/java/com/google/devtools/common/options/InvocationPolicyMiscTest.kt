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
package com.google.devtools.common.options

import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy

/** Miscellaneous tests for [InvocationPolicy]  */
@RunWith(JUnit4::class)
class InvocationPolicyMiscTest : InvocationPolicyEnforcerTestBase() {
    /**
     * Test that deprecated flags set via setValue in the invocation policy don't elicit an extra
     * deprecation warning on top of the one elicted by the user setting the flag.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoPrintDeprecationWarning_setValue() {
        parser.parse("--test_deprecated=" + TEST_DEPRECATED_USER_VALUE)
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_deprecated")
            .getUseDefaultBuilder()
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        assertThat(parser.getWarnings())
            .containsExactly(
                "Option 'test_deprecated' is deprecated: Flag for testing deprecation behavior."
            )
    }

    /**
     * Test that deprecated flags set via UseDefault in the invocation policy don't elicit an extra
     * deprecation warning on top of the one elicted by the user setting the flag.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoPrintDeprecationWarning_useDefault() {
        parser.parse("--test_deprecated=" + TEST_DEPRECATED_USER_VALUE)
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_deprecated")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_DEPRECATED_POLICY_VALUE)
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        assertThat(parser.getWarnings())
            .containsExactly(
                "Option 'test_deprecated' is deprecated: Flag for testing deprecation behavior."
            )
    }

    /**
     * Test that deprecated flags touched via UseDefault in the invocation policy don't elicit a
     * deprecation warning.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDontPrintDeprecationWarning_useDefault() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_deprecated")
            .getUseDefaultBuilder()
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        assertThat(parser.getWarnings()).isEmpty()
    }

    /* Test that deprecated flags set via SetValue in the invocation policy don't elicit a
  deprecation warning. */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDontPrintDeprecatioNWarning_setValue() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_deprecated")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_DEPRECATED_POLICY_VALUE)
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        assertThat(parser.getWarnings()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagPolicy_oldNameAndNewName_oldNameLast() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_new_and_old_name")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue("new_value")
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_old_name")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue("old_value")
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        Truth.assertThat(getTestOptions().getTestNewAndOldName()).isEqualTo("old_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagPolicy_oldNameAndNewName_newNameLast() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_old_name")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue("old_value")
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_new_and_old_name")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue("new_value")
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        Truth.assertThat(getTestOptions().getTestNewAndOldName()).isEqualTo("new_value")
    }

    companion object {
        private const val BUILD_COMMAND = "build"
        private const val TEST_DEPRECATED_USER_VALUE = "user value"
        private const val TEST_DEPRECATED_POLICY_VALUE = "policy value"
    }
}
