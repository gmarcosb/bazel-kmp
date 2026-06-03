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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Tests for [GraphBackedRecursivePackageProvider].  */
@RunWith(TestParameterInjector::class)
class GraphBackedRecursivePackageProviderTest : BuildViewTestCase() {
    @Throws(java.lang.InterruptedException::class)
    private fun makeWalkableGraph(vararg roots: SkyKey?): WalkableGraph {
        val result: EvaluationResult<*> =
            getSkyframeExecutor()
                .evaluate(
                    com.google.common.collect.ImmutableList.< E > copyOf < E ? > (roots),  /* keepGoing= */
                    true,  /* numThreads= */
                    SkyframeExecutor.DEFAULT_THREAD_COUNT,
                    reporter
                )
        return result.getWalkableGraph()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun makeGraphBackedRecursivePackageProvider(
        walkableGraph: WalkableGraph?
    ): GraphBackedRecursivePackageProvider {
        return GraphBackedRecursivePackageProvider(
            walkableGraph,
            UniverseTargetPattern.all(),
            getSkyframeExecutor().getPackageManager().getPackagePath(),
            RecursivePkgValueRootPackageExtractor()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getBuildFile_eagerMacroExpansion() {
        scratch.file("pkg1/BUILD", "filegroup(name = 'foo')")

        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo("pkg1")
        val packageProvider: GraphBackedRecursivePackageProvider =
            makeGraphBackedRecursivePackageProvider(makeWalkableGraph(pkgId))
        val buildFile: InputFile =
            packageProvider.getBuildFile(reporter, PackageIdentifier.createInMainRepo("pkg1"))
        assertThat(buildFile.getName()).isEqualTo("BUILD")
        assertThat(buildFile.getPackageoid()).isInstanceOf(Package::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getBuildFile_lazyMacroExpansion(@TestParameter graphContainsFullPackage: Boolean) {
        setPackageOptions("--experimental_lazy_macro_expansion_packages=*")
        scratch.file("pkg1/BUILD", "filegroup(name = 'foo')")

        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo("pkg1")
        val packagePieceId: PackagePieceIdentifier.ForBuildFile =
            ForBuildFile(pkgId)
        val graph: WalkableGraph = makeWalkableGraph(if (graphContainsFullPackage) pkgId else packagePieceId)
        if (graphContainsFullPackage) {
            assertThat(graph.getValue(pkgId)).isNotNull()
        } else {
            assertThat(graph.getValue(pkgId)).isNull()
        }
        val packageProvider: GraphBackedRecursivePackageProvider =
            makeGraphBackedRecursivePackageProvider(graph)
        val buildFile: InputFile =
            packageProvider.getBuildFile(reporter, PackageIdentifier.createInMainRepo("pkg1"))
        assertThat(buildFile.getName()).isEqualTo("BUILD")
        assertThat(buildFile.getPackageoid()).isInstanceOf(PackagePiece.ForBuildFile::class.java)
    }
}
