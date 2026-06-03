// Copyright 2014 The Bazel Authors. All rights reserved.
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

/** Unit tests for [PackageIdentifier].  */
@RunWith(JUnit4::class)
class PackageIdentifierTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsing() {
        val fooA: PackageIdentifier = PackageIdentifier.parse("@foo//a")
        assertThat(fooA.getRepository().name).isEqualTo("foo")
        assertThat(fooA.getPackageFragment().getPathString()).isEqualTo("a")
        assertThat(fooA.getPackagePath(false)).isEqualTo(PathFragment.create("external/foo/a"))
        assertThat(fooA.getPackagePath(true)).isEqualTo(PathFragment.create("a"))

        val absoluteA: PackageIdentifier = PackageIdentifier.parse("//a")
        assertThat(absoluteA.getRepository().name).isEmpty()
        assertThat(absoluteA.getPackageFragment().getPathString()).isEqualTo("a")
        assertThat(absoluteA.getPackagePath(false)).isEqualTo(PathFragment.create("a"))
        assertThat(absoluteA.getPackagePath(true)).isEqualTo(PathFragment.create("a"))

        val plainA: PackageIdentifier = PackageIdentifier.parse("a")
        assertThat(plainA.getRepository().name).isEmpty()
        assertThat(plainA.getPackageFragment().getPathString()).isEqualTo("a")
        assertThat(plainA.getPackagePath(false)).isEqualTo(PathFragment.create("a"))
        assertThat(plainA.getPackagePath(true)).isEqualTo(PathFragment.create("a"))

        val mainA: PackageIdentifier = PackageIdentifier.parse("@//a")
        assertThat(mainA.getRepository()).isEqualTo(RepositoryName.MAIN)
        assertThat(mainA.getPackageFragment().getPathString()).isEqualTo("a")
        assertThat(mainA.getPackagePath(false)).isEqualTo(PathFragment.create("a"))
        assertThat(mainA.getPackagePath(true)).isEqualTo(PathFragment.create("a"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToString() {
        val local: PackageIdentifier = PackageIdentifier.create("", PathFragment.create("bar/baz"))
        assertThat(local.toString()).isEqualTo("bar/baz")
        val external: PackageIdentifier = PackageIdentifier.create("foo", PathFragment.create("bar/baz"))
        assertThat(external.toString()).isEqualTo("@@foo//bar/baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompareTo() {
        val foo1: PackageIdentifier = PackageIdentifier.create("foo", PathFragment.create("bar/baz"))
        val foo2: PackageIdentifier? = PackageIdentifier.create("foo", PathFragment.create("bar/baz"))
        val foo3: PackageIdentifier? = PackageIdentifier.create("foo", PathFragment.create("bar/bz"))
        val bar: PackageIdentifier? = PackageIdentifier.create("bar", PathFragment.create("bar/baz"))
        assertThat(foo1.compareTo(foo2)).isEqualTo(0)
        assertThat(foo1.compareTo(foo3)).isLessThan(0)
        assertThat(foo1.compareTo(bar)).isGreaterThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidPackageName() {
        // This shouldn't throw an exception, package names aren't validated.
        PackageIdentifier.create("foo", PathFragment.create("bar.baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFragmentEquality() {
        // Make sure package fragments are canonicalized.
        val p1: PackageIdentifier = PackageIdentifier.create("whatever", PathFragment.create("foo/bar"))
        val p2: PackageIdentifier = PackageIdentifier.create("whatever", PathFragment.create("foo/bar"))
        assertThat(p1.getPackageFragment()).isSameInstanceAs(p2.getPackageFragment())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesDir() {
        assertThat(PackageIdentifier.create("foo", PathFragment.create("bar/baz")).getRunfilesPath())
            .isEqualTo(PathFragment.create("../foo/bar/baz"))
        assertThat(PackageIdentifier.create("", PathFragment.create("bar/baz")).getRunfilesPath())
            .isEqualTo(PathFragment.create("bar/baz"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnambiguousCanonicalForm() {
        assertThat(PackageIdentifier.createInMainRepo("foo/bar").getUnambiguousCanonicalForm())
            .isEqualTo("@@//foo/bar")
        assertThat(
            PackageIdentifier.create("foo", PathFragment.create("bar"))
                .getUnambiguousCanonicalForm()
        )
            .isEqualTo("@@foo//bar")
        assertThat(
            PackageIdentifier.create(
                RepositoryName.create("foo").toNonVisible(RepositoryName.create("bar")),
                PathFragment.create("baz")
            )
                .getUnambiguousCanonicalForm()
        )
            .isEqualTo("@@[unknown repo 'foo' requested from @@bar]//baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisplayFormInMainRepository() {
        val pkg: PackageIdentifier =
            PackageIdentifier.create(RepositoryName.MAIN, PathFragment.create("some/pkg"))

        assertThat(
            pkg.getDisplayForm(
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("foo", RepositoryName.create("bar")),
                    RepositoryName.MAIN
                )
            )
        )
            .isEqualTo("//some/pkg")
        assertThat(pkg.getDisplayForm(null)).isEqualTo("//some/pkg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisplayFormInExternalRepository() {
        val repo: RepositoryName = RepositoryName.create("canonical")
        val pkg: PackageIdentifier = PackageIdentifier.create(repo, PathFragment.create("some/pkg"))

        assertThat(
            pkg.getDisplayForm(
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("local", repo),
                    RepositoryName.MAIN
                )
            )
        )
            .isEqualTo("@local//some/pkg")
        assertThat(
            pkg.getDisplayForm(
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("local", RepositoryName.create("other_repo")),
                    RepositoryName.MAIN
                )
            )
        )
            .isEqualTo("@@canonical//some/pkg")
        assertThat(pkg.getDisplayForm(null)).isEqualTo("@@canonical//some/pkg")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization() {
        SerializationTester(PackageIdentifier.parse("@foo//a")).runTests()
    }
}
