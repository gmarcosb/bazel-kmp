// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.analysis.VersionedChanges.CLIENT_CHANGE

@RunWith(JUnit4::class)
class FileSystemDependenciesTest {
    @org.junit.Test
    fun fileDependencies_findEarliestMatch_matchesClientChange() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>("abc/def"))
        val dependencies: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def").build()

        assertThat(dependencies.findEarliestMatch(changes, 0)).isEqualTo(CLIENT_CHANGE)
    }

    @org.junit.Test
    fun fileDependencies_findEarliestMatch_honorsValidityHorizon() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
        changes.registerFileChange("abc/def", 100)

        val dependencies: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def").build()

        assertThat(dependencies.findEarliestMatch(changes, 99)).isEqualTo(100)
        assertThat(dependencies.findEarliestMatch(changes, 100)).isEqualTo(NO_MATCH)
    }

    @org.junit.Test
    fun fileDependencies_withMultiplePaths_findEarliestMatch_honorsValidityHorizon() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
        changes.registerFileChange("abc/def", 100)
        changes.registerFileChange("foo/bar", 99)

        val dependencies: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def").addPath("foo/bar").build()

        assertThat(dependencies.findEarliestMatch(changes, 98)).isEqualTo(99)
        assertThat(dependencies.findEarliestMatch(changes, 99)).isEqualTo(100)
        assertThat(dependencies.findEarliestMatch(changes, 100)).isEqualTo(NO_MATCH)
    }

    @org.junit.Test
    fun listingDependencies_findEarliestMatch_matchesClientChange() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>("abc/def"))
        val dependencies: AvailableListingDependencies =
            ListingDependencies.from(FileDependencies.builder("abc").build()) as AvailableListingDependencies

        assertThat(dependencies.findEarliestMatch(changes, 0)).isEqualTo(CLIENT_CHANGE)
    }

    @org.junit.Test
    fun listingDependencies_findEarliestMatch_honorsValidityHorizon() {
        val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
        changes.registerFileChange("abc/def", 99)
        changes.registerFileChange("abc/ghi", 100)

        val dependencies: AvailableListingDependencies =
            ListingDependencies.from(FileDependencies.builder("abc").build()) as AvailableListingDependencies

        assertThat(dependencies.findEarliestMatch(changes, 100)).isEqualTo(NO_MATCH)
        assertThat(dependencies.findEarliestMatch(changes, 99)).isEqualTo(100)
        assertThat(dependencies.findEarliestMatch(changes, 98)).isEqualTo(99)
    }
}
