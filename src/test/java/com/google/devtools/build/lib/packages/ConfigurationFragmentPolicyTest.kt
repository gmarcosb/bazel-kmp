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

import com.google.devtools.build.lib.analysis.config.Fragment

/**
 * Tests for the ConfigurationFragmentPolicy builder and methods.
 */
@RunWith(JUnit4::class)
class ConfigurationFragmentPolicyTest {
    @StarlarkBuiltin(name = "test_fragment", doc = "first fragment")
    private class TestFragment : StarlarkValue

    @StarlarkBuiltin(name = "other_fragment", doc = "second fragment")
    private class OtherFragment : StarlarkValue

    @StarlarkBuiltin(name = "unknown_fragment", doc = "useless waste of permgen")
    private class UnknownFragment : StarlarkValue

    private class FragmentA : Fragment()

    private class FragmentB : Fragment()

    private class FragmentC : Fragment()

    @org.junit.Test
    fun testMissingFragmentPolicy() {
        val policy: ConfigurationFragmentPolicy =
            Builder()
                .setMissingFragmentPolicy(FragmentA::class.java, MissingFragmentPolicy.IGNORE)
                .build()

        assertThat(policy.getMissingFragmentPolicy(FragmentA::class.java))
            .isEqualTo(MissingFragmentPolicy.IGNORE)

        val otherPolicy: ConfigurationFragmentPolicy =
            Builder()
                .setMissingFragmentPolicy(FragmentB::class.java, MissingFragmentPolicy.CREATE_FAIL_ACTIONS)
                .build()

        assertThat(otherPolicy.getMissingFragmentPolicy(FragmentB::class.java))
            .isEqualTo(MissingFragmentPolicy.CREATE_FAIL_ACTIONS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequiresConfigurationFragments_addsToRequiredSet() {
        // Although these aren't configuration fragments, there are no requirements as to what the class
        // has to be, so...
        val policy: ConfigurationFragmentPolicy =
            Builder()
                .requiresConfigurationFragments(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        FragmentA::class.java,
                        FragmentB::class.java
                    )
                )
                .requiresConfigurationFragments(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        FragmentB::class.java,
                        FragmentC::class.java
                    )
                )
                .build()

        assertThat(policy.getRequiredConfigurationFragments())
            .containsExactly(FragmentA::class.java, FragmentB::class.java, FragmentC::class.java)
    }

    @org.junit.Test
    fun testRequiresConfigurationFragments_mapSetsLegalityByStarlarkModuleName_noRequires() {
        val policy: ConfigurationFragmentPolicy =
            Builder()
                .requiresConfigurationFragmentsByStarlarkBuiltinName(com.google.common.collect.ImmutableSet.of<E?>("test_fragment"))
                .build()

        assertThat(policy.getRequiredConfigurationFragments()).isEmpty()

        assertThat(policy.isLegalConfigurationFragment(com.google.devtools.build.lib.packages.ConfigurationFragmentPolicyTest.TestFragment::class.java)).isTrue()
        assertThat(policy.isLegalConfigurationFragment(com.google.devtools.build.lib.packages.ConfigurationFragmentPolicyTest.TestFragment::class.java)).isTrue()

        assertThat(policy.isLegalConfigurationFragment(OtherFragment::class.java)).isFalse()

        assertThat(policy.isLegalConfigurationFragment(UnknownFragment::class.java)).isFalse()
        assertThat(policy.isLegalConfigurationFragment(UnknownFragment::class.java)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludeConfigurationFragmentsFrom_mergesWithExistingFragmentSet() {
        val basePolicy: ConfigurationFragmentPolicy? =
            Builder()
                .requiresConfigurationFragmentsByStarlarkBuiltinName(com.google.common.collect.ImmutableSet.of<E?>("test_fragment"))
                .requiresConfigurationFragments(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        FragmentA::class.java,
                        FragmentB::class.java
                    )
                )
                .build()
        val addedPolicy: ConfigurationFragmentPolicy? =
            Builder()
                .requiresConfigurationFragmentsByStarlarkBuiltinName(com.google.common.collect.ImmutableSet.of<E?>("other_fragment"))
                .requiresConfigurationFragments(com.google.common.collect.ImmutableSet.of<E?>(FragmentC::class.java))
                .build()
        val combinedPolicy: ConfigurationFragmentPolicy =
            Builder()
                .includeConfigurationFragmentsFrom(basePolicy)
                .includeConfigurationFragmentsFrom(addedPolicy)
                .build()

        assertThat(combinedPolicy.getRequiredConfigurationFragments())
            .containsExactly(FragmentA::class.java, FragmentB::class.java, FragmentC::class.java)
        assertThat(combinedPolicy.isLegalConfigurationFragment(com.google.devtools.build.lib.packages.ConfigurationFragmentPolicyTest.TestFragment::class.java)).isTrue()
        assertThat(combinedPolicy.isLegalConfigurationFragment(OtherFragment::class.java)).isTrue()
    }
}
