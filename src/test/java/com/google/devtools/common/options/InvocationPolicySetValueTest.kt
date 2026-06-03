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

/** Test InvocationPolicies with the SetValues operation.  */
@RunWith(TestParameterInjector::class)
class InvocationPolicySetValueTest : InvocationPolicyEnforcerTestBase() {
    /** Tests that policy overwrites a value when that value is from the user.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_overwritesUserSetting() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_POLICY_VALUE)
        assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map({ obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
        )
            .containsExactly(
                "--test_string=" + TEST_STRING_USER_VALUE, "--test_string=" + TEST_STRING_POLICY_VALUE
            )
            .inOrder()
    }

    /**
     * Tests that policy overwrites a value when the user doesn't specify the value (i.e., the value
     * is from the flag's default from its definition).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_overwritesDefault() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)

        // No user value.
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)

        // All the flags should be their default value.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_POLICY_VALUE)
    }

    /** Tests that SetValue overwrites the user's value when the flag allows multiple values.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_flagWithMultipleValues_overwritesUserSetting() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse(
            "--test_multiple_string=" + TEST_STRING_USER_VALUE,
            "--test_multiple_string=" + TEST_STRING_USER_VALUE_2
        )

        // Options should not be modified by running the parser through OptionsPolicyEnforcer.create().
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(TEST_STRING_USER_VALUE, TEST_STRING_USER_VALUE_2)
            .inOrder()

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(TEST_STRING_POLICY_VALUE, TEST_STRING_POLICY_VALUE_2)
            .inOrder()
    }

    /**
     * Tests that policy overwrites the default value when the flag allows multiple values and the
     * user doesn't provide a value.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_flagWithMultipleValues_overwritesDefault() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        // No user value.
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)

        // Repeatable flags always default to the empty list.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString()).isEmpty()

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Options should now be the values from the policy.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(TEST_STRING_POLICY_VALUE, TEST_STRING_POLICY_VALUE_2)
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setMultipleValuesForSingleValuedFlag_fails(@TestParameter behavior: Behavior?) {
        TruthJUnit.assume().that(behavior).isNotEqualTo(Behavior.UNDEFINED)
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string") // Not repeatable flag.
            .getSetValueBuilder()
            .setBehavior(behavior)
            .addFlagValue(TEST_STRING_POLICY_VALUE) // Has multiple values.
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)

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
    fun append_appendsToMultipleValuedFlag() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.APPEND)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse(
            "--test_multiple_string=" + TEST_STRING_USER_VALUE,
            "--test_multiple_string=" + TEST_STRING_USER_VALUE_2
        )

        // Options should not be modified by running the parser through OptionsPolicyEnforcer.create().
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(TEST_STRING_USER_VALUE, TEST_STRING_USER_VALUE_2)
            .inOrder()

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(
                TEST_STRING_USER_VALUE,
                TEST_STRING_USER_VALUE_2,
                TEST_STRING_POLICY_VALUE,
                TEST_STRING_POLICY_VALUE_2
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setFlagWithExpansion_finalValueIgnoreOverrides_setsExpandedValuesAsFinal(
        @TestParameter("null", "", "some value") value: String?
    ) {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        val setValue: SetValue.Builder =
            invocationPolicy
                .addFlagPoliciesBuilder()
                .setFlagName("test_expansion") // SetValue must have no values for a Void flag.
                .getSetValueBuilder()
                .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
        if (value != null) {
            setValue.addFlagValue(value)
        }

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        // Unrelated flag, but --test_expansion is not set
        parser.parse("--test_string=throwaway value")

        // The flags that --test_expansion expands into should still be their default values
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_DEFAULT)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, the flags should be the values from --test_expansion
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_flagExpandingToExpansion_setsRecursiveValues() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_recursive_expansion_top_level")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        // Unrelated flag, but --test_expansion is not set
        parser.parse("--test_string=throwaway value")

        // The flags that --test_expansion expands into should still be their default values
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_DEFAULT)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, the flags should be the values from the expansion flag
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_RECURSIVE_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_RECURSIVE_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_RECURSIVE_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_RECURSIVE_EXPANSION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowOverrides_setFlagWithExpansion_keepsUserSpecifiedFlag() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_expansion")
            .getSetValueBuilder()
            .setBehavior(Behavior.ALLOW_OVERRIDES)
            .addFlagValue("") // this value is arbitrary, the value for a Void flag is ignored

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        // Unrelated flag, but --test_expansion is not set
        parser.parse("--expanded_c=23")

        // The flags that --test_expansion expands into should still be their default values
        // except for the explicitly marked flag.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC()).isEqualTo(23)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, the flags should be the values from --test_expansion,
        // except for the user-set value, since the expansion flag was set to overridable.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC()).isEqualTo(23)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowOverrides_flagExpandingToRepeatingFlag_appendsRepeatedValues() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_expansion_to_repeatable")
            .getSetValueBuilder() // SetValue must have no values for a Void flag.
            .setBehavior(Behavior.ALLOW_OVERRIDES)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        // Unrelated flag, but --test_expansion is not set
        parser.parse("--test_multiple_string=foo")

        // The flags that --test_expansion expands into should still be their default values
        // except for the explicitly marked flag.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString()).containsExactly("foo")

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, the flags should be the values from --test_expansion,
        // except for the user-set value, since the expansion flag was set to overridable.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(
                "foo",
                com.google.devtools.common.options.TestOptions.Companion.EXPANDED_MULTIPLE_1,
                com.google.devtools.common.options.TestOptions.Companion.EXPANDED_MULTIPLE_2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_setFlagWithExpansion_overwritesUserSettingForExpandedFlag() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_expansion") // SetValue must have no values for a Void flag.
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        // Unrelated flag, but --test_expansion is not set
        parser.parse("--expanded_c=23")

        // The flags that --test_expansion expands into should still be their default values
        // except for the explicitly marked flag.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC()).isEqualTo(23)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, the flags should be the values from --test_expansion,
        // including the value that the user tried to set, since the expansion flag was set
        // non-overridably.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_flagWithExpansionToRepeatingFlag_overwritesUserSetting() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_expansion_to_repeatable") // SetValue must have no values for a Void flag.
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        // Unrelated flag, but --test_expansion is not set
        parser.parse("--test_multiple_string=foo")

        // The flags that --test_expansion expands into should still be their default values
        // except for the explicitly marked flag.
        Truth.assertThat(getTestOptions().getTestMultipleString()).contains("foo")

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, the flag should no longer have the user's value.
        Truth.assertThat(getTestOptions().getTestMultipleString())
            .containsExactly(
                com.google.devtools.common.options.TestOptions.Companion.EXPANDED_MULTIPLE_1,
                com.google.devtools.common.options.TestOptions.Companion.EXPANDED_MULTIPLE_2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_overwritesUserSettingFromExpandedFlag() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("expanded_c")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue("64")

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse("--test_expansion")

        // --test_expansion should set the values from its expansion
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, expanded_c should be set to 64 from the policy, but the
        // flags should remain the same from the expansion of --test_expansion.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC()).isEqualTo(64)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueIgnoreOverrides_overwritesImplicitRequirementFromUserSetting() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("implicit_requirement_a")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse("--test_implicit_requirement=" + TEST_STRING_USER_VALUE)

        // test_implicit_requirement sets implicit_requirement_a to "foo"
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement()).isEqualTo(TEST_STRING_USER_VALUE)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_REQUIRED)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement()).isEqualTo(TEST_STRING_USER_VALUE)
        Truth.assertThat(testOptions.getImplicitRequirementA()).isEqualTo(TEST_STRING_POLICY_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetValueWithImplicitlyRequiredFlags() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_implicit_requirement")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_IGNORE_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--implicit_requirement_a=" + TEST_STRING_USER_VALUE)
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getImplicitRequirementA()).isEqualTo(TEST_STRING_USER_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement()).isEqualTo(TEST_STRING_POLICY_VALUE)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_REQUIRED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowOverrides_leavesUserSetting() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.ALLOW_OVERRIDES)
            .addFlagValue(TEST_STRING_POLICY_VALUE)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Even though the policy sets the value for test_string, the policy is overridable and the
        // user set the value, so it should be the user's value.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun setFlagValueWithNoValue_fails(@TestParameter behavior: Behavior?) {
        TruthJUnit.assume().that(behavior).isNotEqualTo(Behavior.UNDEFINED)
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getSetValueBuilder()
            .setBehavior(behavior) // No value.

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

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
    fun enforce_setValueWithUndefinedBehavior_fails(
        @TestParameter hasBehavior: Boolean,
        @TestParameter("test_string", "test_expansion", "test_implicit_requirement") flagName: String?
    ) {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        val setValue: SetValue.Builder =
            invocationPolicy
                .addFlagPoliciesBuilder()
                .setFlagName(flagName)
                .getSetValueBuilder()
                .addFlagValue("any value")
        if (hasBehavior) {
            setValue.setBehavior(Behavior.UNDEFINED)
        }
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)

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
            .startsWith(
                String.format(
                    ("SetValue operation from invocation policy for has an undefined behavior:"
                            + " flag_name: \"%s\"\n"
                            + "set_value {\n"),
                    flagName
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforce_policySettingConfig_fails(@TestParameter behavior: Behavior?) {
        TruthJUnit.assume().that(behavior).isNotEqualTo(Behavior.UNDEFINED)
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("config")
            .getSetValueBuilder()
            .setBehavior(behavior)
            .addFlagValue("foo")

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse()
        val expected: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    enforcer.enforce(
                        parser,
                        BUILD_COMMAND,
                        com.google.common.collect.ImmutableList.builder<E?>()
                    )
                })
        Truth.assertThat(expected)
            .hasMessageThat()
            .startsWith(
                ("Invocation policy is applied after --config expansion, changing config values now "
                        + "would have no effect and is disallowed to prevent confusion. Please remove "
                        + "the following policy : flag_name: \"config\"\n"
                        + "set_value {\n"
                        + "  flag_value: \"foo\"\n"
                        + "  behavior: "
                        + behavior
                        + "\n")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun enforce_setValueForNonexistentFlag_doesNothing(@TestParameter behavior: Behavior?) {
        TruthJUnit.assume().that(behavior).isNotEqualTo(Behavior.UNDEFINED)
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("nonexistent")
            .getSetValueBuilder()
            .setBehavior(behavior)
            .addFlagValue("hello")
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse()

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        Truth.assertThat(getTestOptions())
            .isEqualTo(com.google.devtools.common.options.Options.getDefaults<O?>(com.google.devtools.common.options.TestOptions::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueThrowOnOverride_throwsOnUserOverride() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .setCustomErrorMessage("See {link to test_string policy} for more details.")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_THROW_ON_OVERRIDE)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

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
            .isEqualTo(
                ("User set a value for option '--test_string' which is not permitted by the invocation"
                        + " policy. This flag value will always be overridden to [policy value]. See"
                        + " {link to test_string policy} for more details.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueThrowOnOverride_successOnNoUserOverride() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .setCustomErrorMessage("See {link to test_string policy} for more details.")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_THROW_ON_OVERRIDE)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo("test string default")

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_POLICY_VALUE)
        assertThat(
            parser.asCompleteListOfParsedOptions().stream()
                .map({ obj: ParsedOptionDescription? -> obj.getCommandLineForm() })
        )
            .containsExactly("--test_string=" + TEST_STRING_POLICY_VALUE)
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueThrowOnOverride_flagWithMultipleValues_throwsOnUserOverride() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .setCustomErrorMessage("See {link to test_multiple_string policy} for more details.")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_THROW_ON_OVERRIDE)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)
        parser.parse(
            "--test_multiple_string=" + TEST_STRING_USER_VALUE,
            "--test_multiple_string=" + TEST_STRING_USER_VALUE_2
        )

        // Options should not be modified by running the parser through OptionsPolicyEnforcer.create().
        val testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(TEST_STRING_USER_VALUE, TEST_STRING_USER_VALUE_2)
            .inOrder()

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
            .isEqualTo(
                ("User set a value for option '--test_multiple_string' which is not permitted by the"
                        + " invocation policy. This flag value will always be overridden to [policy value,"
                        + " policy value 2]. See {link to test_multiple_string policy} for more details.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun finalValueThrowOnOverride_flagWithMultipleValues_successOnNoUserOverride() {
        val invocationPolicy: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicy
            .addFlagPoliciesBuilder()
            .setFlagName("test_multiple_string")
            .getSetValueBuilder()
            .setBehavior(Behavior.FINAL_VALUE_THROW_ON_OVERRIDE)
            .addFlagValue(TEST_STRING_POLICY_VALUE)
            .addFlagValue(TEST_STRING_POLICY_VALUE_2)

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicy)

        // Options should not be modified by running the parser through OptionsPolicyEnforcer.create().
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString()).isEmpty()

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestMultipleString())
            .containsExactly(TEST_STRING_POLICY_VALUE, TEST_STRING_POLICY_VALUE_2)
            .inOrder()
    }

    companion object {
        const val BUILD_COMMAND: String = "build"
        const val TEST_STRING_USER_VALUE: String = "user value"
        const val TEST_STRING_USER_VALUE_2: String = "user value 2"
        const val TEST_STRING_POLICY_VALUE: String = "policy value"
        const val TEST_STRING_POLICY_VALUE_2: String = "policy value 2"
    }
}
