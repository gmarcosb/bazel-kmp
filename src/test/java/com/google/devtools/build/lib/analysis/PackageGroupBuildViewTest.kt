// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.configuredtargets.PackageGroupConfiguredTarget

/**
 * Tests for [PackageGroupConfiguredTarget].
 */
@RunWith(JUnit4::class)
class PackageGroupBuildViewTest : BuildViewTestCase() {
    override fun allowExternalRepositories(): Boolean {
        return true
    }

    /** Regression test for bug #3445835.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupInDeps() {
        checkError(
            "foo",
            "bar",
            "in deps attribute of cc_library rule //foo:bar: "
                    + "package group '//foo:foo' is misplaced here ",
            "package_group(name = 'foo', packages = ['//none'])",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'bar', deps = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupInData() {
        checkError(
            "foo",
            "bar",
            "in data attribute of cc_library rule //foo:bar: "
                    + "package group '//foo:foo' is misplaced here ",
            "package_group(name = 'foo', packages = ['//none'])",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'bar', data = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupWithAllPackagesInMainRepository() {
        scratch.file(
            "fruits/BUILD", "package_group(", "    name = 'apple',", "    packages = ['@//...'],", ")"
        )

        val pg: PackageGroupConfiguredTarget? =
            getConfiguredTarget("//fruits:apple") as PackageGroupConfiguredTarget?
        val provider: PackageSpecificationProvider = pg.getProvider(PackageSpecificationProvider::class.java)
        assertThat(provider.targetInAllowlist(Label.parseCanonical("//any/pkg:target"))).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupWithRepoMapping() {
        registry.addModule(BzlmodTestUtil.createModuleKey("veggies", "1.0"), "module(name='veggies', version='1.0')")

        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='main', version='1.0')",
            "bazel_dep(name='veggies', version='1.0', repo_name='my_veggies')"
        )

        invalidatePackages()

        scratch.file(
            "fruits/BUILD",
            "package_group(",
            "    name = 'banana',",
            "    packages = ['@my_veggies//cucumber'],",
            ")"
        )

        val pg: PackageGroupConfiguredTarget? =
            getConfiguredTarget("//fruits:banana") as PackageGroupConfiguredTarget?
        val provider: PackageSpecificationProvider = pg.getProvider(PackageSpecificationProvider::class.java)

        assertThat(
            provider.targetInAllowlist(
                Label.parseWithRepoContext(
                    "@my_veggies//cucumber:something",
                    Label.RepoContext.of(
                        pg.getLabel().getRepository(),
                        skyframeExecutor.getMainRepoMapping(reporter)
                    )
                )
            )
        )
            .isTrue()
    }
}
