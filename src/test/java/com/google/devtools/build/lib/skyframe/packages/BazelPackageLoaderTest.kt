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
package com.google.devtools.build.lib.skyframe.packages

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Simple tests for [BazelPackageLoader].
 * 
 * 
 * Bazel's unit and integration tests do consistency checks with [BazelPackageLoader] under
 * the covers, so we get pretty exhaustive correctness tests for free.
 */
@RunWith(JUnit4::class)
class BazelPackageLoaderTest : AbstractPackageLoaderTest() {
    private var installBase: Path? = null
    private var outputBase: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        installBase = fs.getPath("/installBase/")
        installBase.createDirectoryAndParents()
        outputBase = fs.getPath("/outputBase/")
        outputBase.createDirectoryAndParents()

        mockEmbeddedTools(installBase)
        fetchExternalRepo(RepositoryName.create("bazel_tools"))

        file("MODULE.bazel", "")
    }

    private fun fetchExternalRepo(externalRepo: RepositoryName?) {
        newPackageLoaderBuilder(root).enableFetchForTesting().build().use { pkgLoaderForFetch ->
            // Load the package '' in this repo. This package may or may not exist; we don't care since we
            // merely need the side-effects of the 'fetch' work.
            val pkgId: PackageIdentifier? = PackageIdentifier.create(externalRepo, PathFragment.create(""))
            try {
                val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    pkgLoaderForFetch.loadPackage(pkgId)
            } catch (e: NoSuchPackageException) {
                // Doesn't matter; see above comment.
            } catch (e: java.lang.InterruptedException) {
            }
        }
    }

    override fun newPackageLoaderBuilder(workspaceDir: Root?): BazelPackageLoader.Builder? {
        return BazelPackageLoader.builder(workspaceDir, installBase, outputBase)
            .useDefaultStarlarkSemantics() as BazelPackageLoader.Builder?
    }

    override fun extractLegacyGlobbingForkJoinPool(packageLoader: PackageLoader): ForkJoinPool {
        return (packageLoader as BazelPackageLoader).forkJoinPoolForNonSkyframeGlobbing
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleLocalRepositoryPackage() {
        file(
            "MODULE.bazel",
            "bazel_dep(name = 'r')",
            "local_path_override(module_name = 'r', path='r')"
        )
        file("r/MODULE.bazel", "module(name = 'r')")
        file("r/good/BUILD", "filegroup(name = 'good')")
        val rRepoName: RepositoryName? = RepositoryName.create("r+")
        fetchExternalRepo(rRepoName)

        val pkgId: PackageIdentifier? = PackageIdentifier.create(rRepoName, PathFragment.create("good"))
        val goodPkg: Package
        val repoMapping: RepositoryMapping
        newPackageLoader().use { pkgLoader ->
            goodPkg = pkgLoader.loadPackage(pkgId)
            repoMapping = pkgLoader.makeLoadingContext().repositoryMapping
        }
        assertThat(goodPkg.containsErrors()).isFalse()
        assertThat(goodPkg.getTarget("good").getAssociatedRule().getRuleClass()).isEqualTo("filegroup")
        assertThat(repoMapping.entries().get("r")).isEqualTo(rRepoName)
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildDotBazelForSubpackageCheckDuringGlobbing() {
        file("a/BUILD", "filegroup(name = 'fg', srcs = glob(['sub/a.txt'], allow_empty = True))")
        file("a/sub/a.txt")
        file("a/sub/BUILD.bazel")

        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(PathFragment.create("a"))
        val aPkg: Package
        newPackageLoader().use { pkgLoader ->
            aPkg = pkgLoader.loadPackage(pkgId)
        }
        assertThat(aPkg.containsErrors()).isFalse()
        org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { aPkg.getTarget("sub/a.txt") })
        MoreAsserts.assertNoEvents(handler.getEvents())
    }

    companion object {
        @Throws(IOException::class)
        private fun mockEmbeddedTools(embeddedBinaries: Path) {
            val tools: Path = embeddedBinaries.getRelative("embedded_tools")
            tools.getRelative("tools/cpp").createDirectoryAndParents()
            tools.getRelative("tools/osx").createDirectoryAndParents()
            FileSystemUtils.writeIsoLatin1(tools.getRelative("MODULE.bazel"), "module(name='bazel_tools')")
            FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/cpp/BUILD"), "")
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/cpp/cc_configure.bzl"),
                "def cc_configure(*args, **kwargs):",
                "    pass"
            )
            FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/osx/BUILD"), "")
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/osx/xcode_configure.bzl"),
                "def xcode_configure(*args, **kwargs):",
                "    pass"
            )
            FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/sh/BUILD"), "")
            FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/build_defs/repo/BUILD"))
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/build_defs/repo/http.bzl"),
                "def http_archive(**kwargs):",
                "  pass",
                "",
                "def http_file(**kwargs):",
                "  pass",
                "",
                "def http_jar(**kwargs):",
                "  pass"
            )
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/build_defs/repo/local.bzl"),
                """
        def _local_repository_impl(rctx):
          path = rctx.workspace_root.get_child(rctx.attr.path)
          rctx.symlink(path, ".")
        local_repository = repository_rule(
            implementation = _local_repository_impl,
            attrs = {"path": attr.string()},
        )

        def new_local_repository(**kwargs):
          pass
        
        """.trimIndent()
            )
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/build_defs/repo/utils.bzl"),
                "def maybe(repo_rule, name, **kwargs):",
                "  if name not in native.existing_rules():",
                "    repo_rule(name = name, **kwargs)"
            )
            FileSystemUtils.writeIsoLatin1(tools.getRelative("tools/jdk/BUILD"))
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/jdk/jdk_build_file.bzl"), "JDK_BUILD_TEMPLATE = ''"
            )
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/jdk/local_java_repository.bzl"),
                "def local_java_repository(**kwargs):",
                "  pass"
            )
            FileSystemUtils.writeIsoLatin1(
                tools.getRelative("tools/jdk/remote_java_repository.bzl"),
                "def remote_java_repository(**kwargs):",
                "  pass"
            )
        }
    }
}
