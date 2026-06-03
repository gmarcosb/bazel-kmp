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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Tests for [EnvironmentGroup]. Note input validation is handled in
 * [PackageFactoryTest].
 */
@RunWith(JUnit4::class)
class EnvironmentGroupTest : PackageLoadingTestCase() {
    private var group: EnvironmentGroup? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createPackage() {
        scratch.file(
            "pkg/BUILD",
            """
        environment(
            name = "foo",
            fulfills = [
                ":bar",
                ":baz",
            ],
        )

        environment(
            name = "bar",
            fulfills = [":baz"],
        )

        environment(name = "baz")

        environment(name = "not_in_group")

        environment_group(
            name = "group",
            defaults = [":foo"],
            environments = [
                ":foo",
                ":bar",
                ":baz",
            ],
        )
        
        """.trimIndent()
        )
        group = getTarget("//pkg:group") as EnvironmentGroup
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGroupMembership() {
        assertThat(group.getEnvironments())
            .isEqualTo(
                com.google.common.collect.ImmutableSet.of<E?>(
                    Label.parseCanonical("//pkg:foo"),
                    Label.parseCanonical("//pkg:bar"),
                    Label.parseCanonical("//pkg:baz")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultsMembership() {
        assertThat(group.getDefaults()).isEqualTo(com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//pkg:foo")))
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val isDefault: Unit
        get() {
            val unpackedGroup: EnvironmentLabels = group.getEnvironmentLabels()
            assertThat(unpackedGroup.isDefault(Label.parseCanonical("//pkg:foo"))).isTrue()
            assertThat(unpackedGroup.isDefault(Label.parseCanonical("//pkg:bar"))).isFalse()
            assertThat(unpackedGroup.isDefault(Label.parseCanonical("//pkg:baz"))).isFalse()
            assertThat(unpackedGroup.isDefault(Label.parseCanonical("//pkg:not_in_group"))).isFalse()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fulfillers() {
        val unpackedGroup: EnvironmentLabels = group.getEnvironmentLabels()
        assertThat(unpackedGroup.getFulfillers(Label.parseCanonical("//pkg:baz")))
            .containsExactly(Label.parseCanonical("//pkg:foo"), Label.parseCanonical("//pkg:bar"))
        assertThat(unpackedGroup.getFulfillers(Label.parseCanonical("//pkg:bar")))
            .containsExactly(Label.parseCanonical("//pkg:foo"))
        assertThat(unpackedGroup.getFulfillers(Label.parseCanonical("//pkg:foo"))).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyGroupsNotAllowed() {
        scratch.file(
            "a/BUILD", "environment_group(name = 'empty_group', environments = [], defaults = [])"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: Packageoid = getTarget("//a:BUILD").getPackageoid()
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "environment group empty_group must contain at least one environment"
        )
    }

    @org.junit.Test
    fun reduceForSerialization_hasConsistentValues() {
        assertThat(group).hasSamePropertiesAs(group.reduceForSerialization())
    }
}
