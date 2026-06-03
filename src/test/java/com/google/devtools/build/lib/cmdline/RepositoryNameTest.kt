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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** Tests for [RepositoryName].  */
@RunWith(JUnit4::class)
class RepositoryNameTest {
    fun assertNotValid(name: String?, expectedMessage: String?) {
        val expected: LabelSyntaxException? =
            org.junit.Assert.assertThrows<T?>(
                LabelSyntaxException::class.java,
                org.junit.function.ThrowingRunnable { RepositoryName.create(name) })
        assertThat(expected).hasMessageThat().contains(expectedMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidateRepositoryName() {
        assertThat(RepositoryName.create("foo").name).isEqualTo("foo")
        assertThat(RepositoryName.create("").name).isEqualTo("")
        assertThat(RepositoryName.create("")).isSameInstanceAs(RepositoryName.MAIN)
        assertThat(RepositoryName.create("foo_bar").name).isEqualTo("foo_bar")
        assertThat(RepositoryName.create("foo-bar").name).isEqualTo("foo-bar")
        assertThat(RepositoryName.create("foo.bar").name).isEqualTo("foo.bar")
        assertThat(RepositoryName.create("..foo").name).isEqualTo("..foo")
        assertThat(RepositoryName.create("foo..").name).isEqualTo("foo..")
        assertThat(RepositoryName.create(".foo").name).isEqualTo(".foo")
        assertThat(RepositoryName.create("foo+bar").name).isEqualTo("foo+bar")

        assertNotValid(".", "repo names are not allowed to be '.'")
        assertNotValid("..", "repo names are not allowed to be '..'")
        assertNotValid("foo/bar", "repo names may contain only A-Z, a-z, 0-9, '-', '_', '.' and '+'")
        assertNotValid("foo@", "repo names may contain only A-Z, a-z, 0-9, '-', '_', '.' and '+'")
        assertNotValid("foo\u0000", "repo names may contain only A-Z, a-z, 0-9, '-', '_', '.' and '+'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateUserProvidedRepoName() {
        RepositoryName.validateUserProvidedRepoName("foo")
        RepositoryName.validateUserProvidedRepoName("foo_bar")
        RepositoryName.validateUserProvidedRepoName("foo-bar")
        RepositoryName.validateUserProvidedRepoName("foo.bar")
        RepositoryName.validateUserProvidedRepoName("foo..")
        RepositoryName.validateUserProvidedRepoName("foo.33")

        org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { RepositoryName.validateUserProvidedRepoName(".foo") })
        org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { RepositoryName.validateUserProvidedRepoName("_foo") })
        org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { RepositoryName.validateUserProvidedRepoName("foo/bar") })
        org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { RepositoryName.validateUserProvidedRepoName("@foo") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesDir() {
        assertThat(RepositoryName.create("foo").getRunfilesPath())
            .isEqualTo(PathFragment.create("../foo"))
        assertThat(RepositoryName.create("").getRunfilesPath()).isEqualTo(PathFragment.EMPTY_FRAGMENT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDefaultCanonicalForm() {
        assertThat(RepositoryName.create("").getCanonicalForm()).isEqualTo("")
        assertThat(RepositoryName.create("foo").getCanonicalForm()).isEqualTo("@@foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDisplayForm() {
        val repositoryMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("local", RepositoryName.create("canonical")),
                RepositoryName.MAIN
            )

        assertThat(RepositoryName.create("").getDisplayForm(repositoryMapping)).isEmpty()
        assertThat(RepositoryName.create("canonical").getDisplayForm(repositoryMapping))
            .isEqualTo("@local")
        assertThat(RepositoryName.create("other").getDisplayForm(repositoryMapping))
            .isEqualTo("@@other")

        assertThat(
            RepositoryName.create("")
                .toNonVisible(RepositoryName.create("owner"))
                .getDisplayForm(repositoryMapping)
        )
            .isEqualTo("@@[unknown repo '' requested from @@owner]")
        assertThat(
            RepositoryName.create("local")
                .toNonVisible(RepositoryName.create("owner"))
                .getDisplayForm(repositoryMapping)
        )
            .isEqualTo("@@[unknown repo 'local' requested from @@owner]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDisplayFormWithNullMapping() {
        assertThat(RepositoryName.create("").getDisplayForm(null)).isEmpty()
        assertThat(RepositoryName.create("canonical").getDisplayForm(null)).isEqualTo("@@canonical")

        assertThat(
            RepositoryName.create("")
                .toNonVisible(RepositoryName.create("owner"))
                .getDisplayForm(null)
        )
            .isEqualTo("@@[unknown repo '' requested from @@owner]")
        assertThat(
            RepositoryName.create("canonical")
                .toNonVisible(RepositoryName.create("owner"))
                .getDisplayForm(null)
        )
            .isEqualTo("@@[unknown repo 'canonical' requested from @@owner]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization() {
        SerializationTester(
            RepositoryName.create("foo"),
            RepositoryName.create("foo").toNonVisible(RepositoryName.create("owner"))
        )
            .runTests()
    }
}
