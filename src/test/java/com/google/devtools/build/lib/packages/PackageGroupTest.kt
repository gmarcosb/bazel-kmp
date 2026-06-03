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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Unit tests for PackageGroup.  */
@RunWith(JUnit4::class)
class PackageGroupTest : PackageLoadingTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotFailHorribly() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["//random"],
        )
        
        """.trimIndent()
        )

        // Note that, for our purposes, the packages listed in the package_group need not exist.
        getPackageGroup("fruits", "apple")
    }

    // Regression test for: "Package group with empty name causes Blaze exception"
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyPackageGroupNameDoesNotThrow() {
        scratch.file(
            "strawberry/BUILD",
            """
        package_group(
            name = "",
            packages = [],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // Call getTarget() directly since getPackageGroup() requires a name.
        getTarget("//strawberry:BUILD")
        assertContainsEvent("package group has invalid name")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsolutePackagesWork() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["//vegetables"],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("fruits", "apple")
        assertThat(grp.contains(pkgId("vegetables"))).isTrue()
        assertThat(grp.contains(pkgId("fruits/vegetables"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackagesWithoutDoubleSlashDoNotWork() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["vegetables"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("fruits", "apple")
        assertContainsEvent("invalid package name 'vegetables'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackagesWithRepositoryWorks() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "banana",
            packages = ["@@veggies//cucumber"],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("fruits", "banana")
        assertThat(grp.contains(pkgId("veggies", "cucumber"))).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllPackagesInMainRepositoryWorks() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["@//..."],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("fruits", "apple")
        assertThat(grp.contains(pkgId("anything"))).isTrue()
    }

    // TODO(brandjon): It'd be nice to include a test here that you can cross repositories via
    // `includes`: if package_group //:A includes package_group @repo//:B that has "//foo" in its
    // `packages`, then //:A admits package @repo//foo. Unfortunately PackageLoadingTestCase doesn't
    // support resolving repos, but similar functionality is tested in
    // BzlLoadFunctionTest#testBzlVisibility_enumeratedPackagesMultipleRepos.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetNameAsPackageDoesNotWork1() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["//vegetables:carrot"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("fruits", "apple")
        assertContainsEvent("invalid package name '//vegetables:carrot'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetNameAsPackageDoesNotWork2() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = [":carrot"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("fruits", "apple")
        assertContainsEvent("invalid package name ':carrot'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllBeneathSpecificationWorks() {
        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "maracuja",
            packages = ["//tropics/..."],
        )
        
        """.trimIndent()
        )

        getPackageGroup("fruits", "maracuja")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegative() {
        scratch.file(
            "test/BUILD",
            """
        package_group(
            name = "packages",
            packages = [
                "-//four",
                "-//three",
                "//one",
                "//two",
            ],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("test", "packages")
        assertThat(grp.contains(pkgId("one"))).isTrue()
        assertThat(grp.contains(pkgId("two"))).isTrue()
        assertThat(grp.contains(pkgId("three"))).isFalse()
        assertThat(grp.contains(pkgId("four"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegative_noSubpackages() {
        scratch.file(
            "test/BUILD",
            """
        package_group(
            name = "packages",
            packages = [
                "-//pkg/one",
                "//pkg/...",
            ],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("test", "packages")
        assertThat(grp.contains(pkgId("pkg"))).isTrue()
        assertThat(grp.contains(pkgId("pkg/one"))).isFalse()
        assertThat(grp.contains(pkgId("pkg/one/two"))).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegative_subpackages() {
        scratch.file(
            "test/BUILD",
            """
        package_group(
            name = "packages",
            packages = [
                "-//pkg/one/...",
                "//pkg/...",
            ],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("test", "packages")
        assertThat(grp.contains(pkgId("pkg"))).isTrue()
        assertThat(grp.contains(pkgId("pkg/one"))).isFalse()
        assertThat(grp.contains(pkgId("pkg/one/two"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEverythingSpecificationWorks() {
        setBuildLanguageOptions("--incompatible_package_group_has_public_syntax=true")

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "mango",
            packages = ["public"],
        )
        
        """.trimIndent()
        )
        val grp: PackageGroup = getPackageGroup("fruits", "mango")

        // Assert that we're using the right package spec.
        assertThat(grp.getContainedPackages( /*includeDoubleSlash=*/true)).containsExactly("public")
        // Assert that this package spec contains packages from both inside and outside the main repo.
        assertThat(grp.contains(pkgId("pkg"))).isTrue()
        assertThat(grp.contains(pkgId("somerepo", "pkg"))).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNothingSpecificationWorks() {
        setBuildLanguageOptions("--incompatible_package_group_has_public_syntax=true")

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "mango",
            packages = ["private"],
        )
        
        """.trimIndent()
        )
        val grp: PackageGroup = getPackageGroup("fruits", "mango")

        // Assert that we're using the right package spec.
        assertThat(grp.getContainedPackages( /*includeDoubleSlash=*/true)).containsExactly("private")
        assertThat(grp.contains(pkgId("anything"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPublicPrivateAreNotAccessibleWithoutFlag() {
        setBuildLanguageOptions( // Flag being tested
            "--incompatible_package_group_has_public_syntax=false",  // Must also be disabled in order to disable the above
            "--incompatible_fix_package_group_reporoot_syntax=false"
        )

        scratch.file(
            "foo/BUILD",
            """
        package_group(
            name = "grp1",
            packages = ["public"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "bar/BUILD",
            """
        package_group(
            name = "grp2",
            packages = ["private"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("foo", "grp1")
        assertContainsEvent(
            "Use of \"public\" package specification requires enabling"
                    + " --incompatible_package_group_has_public_syntax"
        )
        getPackageGroup("bar", "grp2")
        assertContainsEvent(
            "Use of \"private\" package specification requires enabling"
                    + " --incompatible_package_group_has_public_syntax"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoRootSubpackagesIsPublic_withoutFlag() {
        setBuildLanguageOptions("--incompatible_fix_package_group_reporoot_syntax=false")

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "mango",
            packages = ["//..."],
        )
        
        """.trimIndent()
        )
        val grp: PackageGroup = getPackageGroup("fruits", "mango")

        // Use includeDoubleSlash=true to make package spec stringification distinguish AllPackages from
        // AllPackagesBeneath with empty package path.
        assertThat(grp.getContainedPackages( /*includeDoubleSlash=*/true)) // Assert that "//..." gave us AllPackages.
            .containsExactly("public")
        assertThat(grp.contains(pkgId("pkg"))).isTrue()
        assertThat(grp.contains(pkgId("somerepo", "pkg"))).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoRootSubpackagesIsNotPublic_withFlag() {
        setBuildLanguageOptions(
            "--incompatible_package_group_has_public_syntax=true",
            "--incompatible_fix_package_group_reporoot_syntax=true"
        )

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "mango",
            packages = ["//..."],
        )
        
        """.trimIndent()
        )
        val grp: PackageGroup = getPackageGroup("fruits", "mango")

        // Use includeDoubleSlash=true to make package spec stringification distinguish AllPackages from
        // AllPackagesBeneath with empty package path.
        assertThat(grp.getContainedPackages( /*includeDoubleSlash=*/true)) // Assert that "//..." gave us AllPackagesBeneath.
            .containsExactly("//...")
        assertThat(grp.contains(pkgId("pkg"))).isTrue()
        assertThat(grp.contains(pkgId("somerepo", "pkg"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotUseNewRepoRootSyntaxWithoutPublicSyntax() {
        setBuildLanguageOptions(
            "--incompatible_package_group_has_public_syntax=false",
            "--incompatible_fix_package_group_reporoot_syntax=true"
        )

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "mango",
            packages = ["//something"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("fruits", "mango")
        assertContainsEvent("Cannot use new \"//...\" meaning without allowing new \"public\" syntax.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegative_repoRootSubpackages() {
        scratch.file(
            "test/BUILD",
            """
        package_group(
            name = "packages",
            packages = [
                "-//...",
                "//pkg/one",
            ],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("test", "packages")
        assertThat(grp.contains(pkgId("pkg"))).isFalse()
        assertThat(grp.contains(pkgId("pkg/one"))).isFalse()
        assertThat(grp.contains(pkgId("pkg/one/two"))).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegative_public() {
        setBuildLanguageOptions("--incompatible_package_group_has_public_syntax=true")

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["-public"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("fruits", "apple")
        assertContainsEvent("Cannot negate \"public\" package specification")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegative_private() {
        setBuildLanguageOptions("--incompatible_package_group_has_public_syntax=true")

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["-private"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getPackageGroup("fruits", "apple")
        assertContainsEvent("Cannot negate \"private\" package specification")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatePackage() {
        scratch.file(
            "test/BUILD",
            """
        package_group(
            name = "packages",
            packages = ["//one/two"],
        )
        
        """.trimIndent()
        )

        val grp: PackageGroup = getPackageGroup("test", "packages")
        assertThat(grp.contains(pkgId("one/two"))).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringification() {
        val main: RepositoryName? = RepositoryName.MAIN
        val other: RepositoryName? = RepositoryName.create("other")
        val contents: PackageGroupContents =
            PackageGroupContents.create(
                com.google.common.collect.ImmutableList.of<E?>(
                    pkgSpec(main, "//a"),
                    pkgSpec(main, "//a/b/..."),
                    pkgSpec(main, "-//c"),
                    pkgSpec(main, "-//c/d/..."),
                    pkgSpec(main, "//..."),
                    pkgSpec(main, "-//..."),
                    pkgSpec(main, "//"),
                    pkgSpec(main, "-//"),
                    pkgSpec(other, "//z"),
                    pkgSpec(other, "//..."),
                    pkgSpec(main, "public"),
                    pkgSpec(main, "private")
                )
            )
        assertThat(contents.packageStrings( /* includeDoubleSlash= */false))
            .containsExactly(
                "a",
                "",
                "@@other//z",
                "a/b/...",
                "//...",
                "@@other//...",
                "-c",
                "-",
                "-c/d/...",
                "-//...",
                "//...",  // legacy syntax for public
                "private"
            )
        assertThat(contents.packageStrings( /* includeDoubleSlash= */true))
            .containsExactly(
                "//a",
                "//a/b/...",
                "-//c",
                "-//c/d/...",
                "//...",
                "-//...",
                "//",
                "-//",
                "@@other//z",
                "@@other//...",
                "public",
                "private"
            )
        assertThat(contents.packageStringsWithDoubleSlashAndWithoutRepository())
            .containsExactly(
                "//a",
                "//a/b/...",
                "-//c",
                "-//c/d/...",
                "//...",
                "-//...",
                "//",
                "-//",
                "//z",
                "//...",
                "public",
                "private"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReduceForSerialization() {
        setBuildLanguageOptions("--incompatible_package_group_has_public_syntax=true")

        scratch.file(
            "fruits/BUILD",
            """
        package_group(
            name = "apple",
            packages = ["//vegetables"],
        )

        package_group(
            name = "mango",
            packages = ["public"],
        )
        
        """.trimIndent()
        )
        var grp: PackageGroup = getPackageGroup("fruits", "apple")
        assertThat(grp).hasSamePropertiesAs(grp.reduceForSerialization())

        grp = getPackageGroup("fruits", "mango")
        assertThat(grp).hasSamePropertiesAs(grp.reduceForSerialization())
    }

    /** Convenience method for obtaining a PackageSpecification.  */
    @Throws(java.lang.Exception::class)
    private fun pkgSpec(repository: RepositoryName?, spec: String?): PackageSpecification {
        return PackageSpecification.fromString(
            RepositoryMapping.EMPTY,
            repository,
            spec,  /* allowPublicPrivate= */
            true,  /* repoRootMeansCurrentRepo= */
            true
        )
    }

    /** Convenience method for obtaining a PackageIdentifier.  */
    @Throws(java.lang.Exception::class)
    private fun pkgId(packageName: String?): PackageIdentifier {
        return PackageIdentifier.createUnchecked( /*repository=*/"", packageName)
    }

    /** Convenience method for obtaining a PackageIdentifier outside the main repo.  */
    @Throws(java.lang.Exception::class)
    private fun pkgId(repoName: String?, packageName: String?): PackageIdentifier {
        return PackageIdentifier.createUnchecked(repoName, packageName)
    }

    /** Evaluates and returns the requested package_group target.  */
    @Throws(java.lang.Exception::class)
    private fun getPackageGroup(pkg: String?, name: String?): PackageGroup {
        return getTarget("//" + pkg + ":" + name) as PackageGroup
    }
}
