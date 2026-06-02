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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.ModuleOverride

/**
 * Test for [RepositoryOptions].
 */
@RunWith(JUnit4::class)
class RepositoryOptionsTest {
    private val converter: RepositoryOverrideConverter = RepositoryOverrideConverter()

    @org.junit.Rule
    var expectedException: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverrideConverter() {
        val actual: RepositoryOverride = converter.convert("foo=/bar")
        assertThat(actual.repositoryName).isEqualTo("foo")
        assertThat(PathFragment.create(actual.path)).isEqualTo(PathFragment.create("/bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverridePathWithEqualsSign() {
        val actual: RepositoryOverride = converter.convert("foo=/bar=/baz")
        assertThat(actual.repositoryName).isEqualTo("foo")
        assertThat(PathFragment.create(actual.path)).isEqualTo(PathFragment.create("/bar=/baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOverridePathWithTilde() {
        val actual: RepositoryOverride = converter.convert("foo=~/bar")
        assertThat(actual.repositoryName).isEqualTo("foo")
        assertThat(PathFragment.create(actual.path))
            .isEqualTo(PathFragment.create(com.google.common.base.StandardSystemProperty.USER_HOME.value() + "/bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModuleOverridePathWithTilde() {
        val converter: ModuleOverrideConverter = ModuleOverrideConverter()
        val actual: ModuleOverride = converter.convert("foo=~/bar")
        assertThat(PathFragment.create(actual.path))
            .isEqualTo(PathFragment.create(com.google.common.base.StandardSystemProperty.USER_HOME.value() + "/bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModuleOverrideRelativePath() {
        val converter: ModuleOverrideConverter = ModuleOverrideConverter()
        var actual: ModuleOverride = converter.convert("foo=%workspace%/bar")
        assertThat(actual.path).isEqualTo("%workspace%/bar")
        actual = converter.convert("foo=../../bar")
        assertThat(actual.path).isEqualTo("../../bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidOverride() {
        expectedException.expect(OptionsParsingException::class.java)
        expectedException.expectMessage(
            "Repository overrides must be of the form 'repository-name=path'"
        )
        converter.convert("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidRepoOverride() {
        expectedException.expect(OptionsParsingException::class.java)
        expectedException.expectMessage("Invalid repository name given to override")
        converter.convert("foo/bar=/baz")
    }
}
