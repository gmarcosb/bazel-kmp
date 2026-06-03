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

/** Test InvocationPolicies with the UseDefault operation.  */
@RunWith(JUnit4::class)
class InvocationPolicyUseDefaultTest : InvocationPolicyEnforcerTestBase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefault() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_string=" + TEST_STRING_USER_VALUE)

        // Options should be the user specified value before enforcing policy.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString()).isEqualTo(TEST_STRING_USER_VALUE)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Get the options again after policy enforcement: The flag should now be back to its default
        // value
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
    }

    /**
     * Tests UseDefault when the user never actually specified the flag.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWhenFlagWasntSet() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()
            .setFlagName("test_string")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)

        // Options should be the default since the user never specified it.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Still the default.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestString())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_STRING_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithExpansionFlags() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()
            .setFlagName("test_expansion")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_expansion")

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

        // After policy enforcement, all the flags that --test_expansion expanded into should be back
        // to their default values.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_DEFAULT)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithExpansionFlagAndLaterOverride() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("test_expansion")
            .getUseDefaultBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("expanded_b")
            .getAllowValuesBuilder()
            .addAllowedValues("false")

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_expansion")

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)

        // If the UseDefault is run, then the value of --expanded_b is back to it's default true, which
        // isn't allowed. However, the allowValues in the later policy should wipe the expansion's
        // policy on --expanded_b, so that the enforcement does not fail.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_DEFAULT)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithRecursiveExpansionFlags() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()
            .setFlagName("test_expansion")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_recursive_expansion_top_level")

        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_RECURSIVE_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_TEST_RECURSIVE_EXPANSION)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_RECURSIVE_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_RECURSIVE_EXPANSION)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // After policy enforcement, all the flags that --test_recursive_expansion_top_level and its
        // recursive expansions set should be back to their default values.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_DEFAULT)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_DEFAULT)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithExpandedFlags() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("expanded_b")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_expansion")

        // --test_expansion should turn set the values from its expansion
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

        // After policy enforcement, expanded_b should be back to its default (true), but the
        // rest should remain the same.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getExpandedA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_A_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedB())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_B_DEFAULT)
        Truth.assertThat(testOptions.getExpandedC())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_C_TEST_EXPANSION)
        Truth.assertThat(testOptions.getExpandedD())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.EXPANDED_D_TEST_EXPANSION)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithFlagWithImplicitRequirements() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()
            .setFlagName("test_implicit_requirement")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_implicit_requirement=" + TEST_STRING_USER_VALUE)

        // test_implicit_requirement sets implicit_requirement_a to "foo", which ignores the user's
        // value because the parser processes implicit values last.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement()).isEqualTo(TEST_STRING_USER_VALUE)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_REQUIRED)

        // Then policy puts test_implicit_requirement and its implicit requirements back to its default.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_IMPLICIT_REQUIREMENT_DEFAULT)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithImplicitlyRequiredFlag() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder
            .addFlagPoliciesBuilder()
            .setFlagName("implicit_requirement_a")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse(
            "--test_implicit_requirement=" + TEST_STRING_USER_VALUE,
            "--implicit_requirement_a=" + TEST_STRING_USER_VALUE
        )

        // test_implicit_requirement sets implicit_requirement_a to "foo", but it gets overwritten
        // by the user value.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement()).isEqualTo(TEST_STRING_USER_VALUE)
        Truth.assertThat(testOptions.getImplicitRequirementA()).isEqualTo(TEST_STRING_USER_VALUE)

        // Then policy puts implicit_requirement_a back to its default. This is "broken" since it wipes
        // the user value, but this is the behavior that was agreed on and is documented for expansion
        // flags as well.
        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestImplicitRequirement()).isEqualTo(TEST_STRING_USER_VALUE)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUseDefaultWithFlagWithRecursiveImplicitRequirements() {
        val invocationPolicyBuilder: InvocationPolicy.Builder = InvocationPolicy.newBuilder()
        invocationPolicyBuilder.addFlagPoliciesBuilder()
            .setFlagName("test_recursive_implicit_requirement")
            .getUseDefaultBuilder()

        val enforcer: InvocationPolicyEnforcer =
            InvocationPolicyEnforcerTestBase.Companion.createOptionsPolicyEnforcer(invocationPolicyBuilder)
        parser.parse("--test_recursive_implicit_requirement=" + TEST_STRING_USER_VALUE)

        // test_recursive_implicit_requirement gets its value from the command line,
        // test_implicit_requirement gets its value from test_recursive_implicit_requirement, and
        // implicit_requirement_a gets its value from test_implicit_requirement.
        var testOptions: com.google.devtools.common.options.TestOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestRecursiveImplicitRequirement()).isEqualTo(TEST_STRING_USER_VALUE)
        Truth.assertThat(testOptions.getTestImplicitRequirement())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_IMPLICIT_REQUIREMENT_REQUIRED)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_REQUIRED)

        enforcer.enforce(parser, BUILD_COMMAND, com.google.common.collect.ImmutableList.builder<E?>())

        // Policy enforcement should set everything back to its default value.
        testOptions = getTestOptions()
        Truth.assertThat(testOptions.getTestRecursiveImplicitRequirement())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_RECURSIVE_IMPLICIT_REQUIREMENT_DEFAULT)
        Truth.assertThat(testOptions.getTestImplicitRequirement())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.TEST_IMPLICIT_REQUIREMENT_DEFAULT)
        Truth.assertThat(testOptions.getImplicitRequirementA())
            .isEqualTo(com.google.devtools.common.options.TestOptions.Companion.IMPLICIT_REQUIREMENT_A_DEFAULT)
    }

    companion object {
        // Useful constants
        const val BUILD_COMMAND: String = "build"
        const val TEST_STRING_USER_VALUE: String = "user value"
    }
}
