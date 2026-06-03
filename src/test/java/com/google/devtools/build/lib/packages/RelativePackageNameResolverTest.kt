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

import com.google.devtools.build.lib.vfs.PathFragment

/**
 * Unit tests for [RelativePackageNameResolver].
 */
@RunWith(JUnit4::class)
class RelativePackageNameResolverTest {
    private var resolver: RelativePackageNameResolver? = null

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePackagesBelowOneLevelWork() {
        createResolver("foo", true)
        assertResolvesTo("bar", "foo/bar")

        createResolver("foo/bar", true)
        assertResolvesTo("pear", "foo/bar/pear")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePackagesBelowTwoLevelsWork() {
        createResolver("foo/bar", true)
        assertResolvesTo("pear", "foo/bar/pear")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePackagesAboveOneLevelWork() {
        createResolver("foo", true)
        assertResolvesTo("../bar", "bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativePackagesAboveTwoLevelsWork() {
        createResolver("foo/bar", true)
        assertResolvesTo("../../apple", "apple")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleAbsolutePackagesWork() {
        createResolver("foo", true)

        assertResolvesTo("//foo", "foo")
        assertResolvesTo("//foo/bar", "foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildNotRemoved() {
        createResolver("foo", false)

        assertResolvesTo("bar/BUILD", "foo/bar/BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildRemoved() {
        createResolver("foo", true)

        assertResolvesTo("bar/BUILD", "foo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyOffset() {
        createResolver("", true)

        assertResolvesTo("bar", "bar")
        assertResolvesTo("bar/qux", "bar/qux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTooFarUpwardsOneLevelThrows() {
        createResolver("foo", true)

        org.junit.Assert.assertThrows<T?>(
            InvalidPackageNameException::class.java,
            org.junit.function.ThrowingRunnable { resolver.resolve("../../bar") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTooFarUpwardsTwoLevelsThrows() {
        createResolver("foo/bar", true)
        assertResolvesTo("../../orange", "orange")

        org.junit.Assert.assertThrows<T?>(
            InvalidPackageNameException::class.java,
            org.junit.function.ThrowingRunnable { resolver.resolve("../../../orange") })
    }

    private fun createResolver(offset: String?, discardBuild: Boolean) {
        resolver = RelativePackageNameResolver(PathFragment.create(offset), discardBuild)
    }

    @Throws(java.lang.Exception::class)
    private fun assertResolvesTo(relative: String?, expectedAbsolute: String?) {
        val result: String? = resolver.resolve(relative)
        Truth.assertThat(result).isEqualTo(expectedAbsolute)
    }
}
