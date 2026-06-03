// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.cmdline

import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [RepositoryMapping].  */
@RunWith(JUnit4::class)
class RepositoryMappingTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun neverFallback() {
        val mapping: RepositoryMapping =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("A", RepositoryName.create("com_foo_bar_a")),
                RepositoryName.create("fake_owner_repo")
            )
        assertThat(mapping.get("A")).isEqualTo(RepositoryName.create("com_foo_bar_a"))
        assertThat(mapping.get("B"))
            .isEqualTo(
                RepositoryName.create("B").toNonVisible(RepositoryName.create("fake_owner_repo"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun additionalMappings_basic() {
        val mapping: RepositoryMapping =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("A", RepositoryName.create("com_foo_bar_a")),
                RepositoryName.create("fake_owner_repo")
            )
                .withAdditionalMappings(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "B",
                        RepositoryName.create("com_foo_bar_b")
                    )
                )
        assertThat(mapping.get("A")).isEqualTo(RepositoryName.create("com_foo_bar_a"))
        assertThat(mapping.get("B")).isEqualTo(RepositoryName.create("com_foo_bar_b"))
        assertThat(mapping.get("C"))
            .isEqualTo(
                RepositoryName.create("C").toNonVisible(RepositoryName.create("fake_owner_repo"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun additionalMappings_precedence() {
        val mapping: RepositoryMapping =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("A", RepositoryName.create("A1")), RepositoryName.MAIN
            )
                .withAdditionalMappings(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "A",
                        RepositoryName.create("A2")
                    )
                )
        assertThat(mapping.get("A")).isEqualTo(RepositoryName.create("A1"))
    }

    @org.junit.Test
    @Throws(LabelSyntaxException::class)
    fun unknownRepoDidYouMean() {
        val mapping: RepositoryMapping =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("foo", RepositoryName.create("foo_internal")),
                RepositoryName.MAIN
            )
        assertThat(mapping.get("boo").getNameWithAt())
            .isEqualTo("@@[unknown repo 'boo' requested from @@ (did you mean 'foo'?)]")
    }
}
