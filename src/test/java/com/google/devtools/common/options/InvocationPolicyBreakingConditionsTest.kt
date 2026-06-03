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

import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy

/** Test InvocationPolicies on cases where we expect it to fail gracefully.  */
@RunWith(JUnit4::class)
class InvocationPolicyBreakingConditionsTest : InvocationPolicyEnforcerTestBase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagPolicyDoesNotApply() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .addCommands("build")
            .getSetValueBuilder()
            .addFlagValue(TEST_STRING_POLICY_VALUE)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

        enforcer.enforce(parser, "test", com.google.common.collect.ImmutableList.builder<E?>())

        // Still user value.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistantFlagFromPolicy() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("i_do_not_exist")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

        enforcer.enforce(parser, "test", com.google.common.collect.ImmutableList.builder<E?>())

        // Still user value.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_POLICY_VALUE_2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOperationNotSet() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()

        // No operations added to the flag policy
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

        // Shouldn't throw.
        enforcer.enforce(parser, "test", com.google.common.collect.ImmutableList.builder<E?>())

        // Still user value.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)
    }

    companion object {
        // Useful constants
        const val TEST_STRING_USER_VALUE: String = "user value"
        const val TEST_STRING_POLICY_VALUE: String = "policy value"
        const val TEST_STRING_POLICY_VALUE_2: String = "policy value 2"
    }
}
