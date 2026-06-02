// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.tests.workspace

import com.google.devtools.build.lib.vfs.FileSystem

/** Tests pkg_tar and http_archive.  */
class BazelEmbeddedStarlarkBlackBoxTest : AbstractBlackBoxTest() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPkgTar() {
        context().write("main/foo.txt", "Hello World")
        context().write("main/bar.txt", "Hello World, again")
        context()
            .write(
                "main/BUILD",
                """
            load("@bazel_tools//tools/build_defs/pkg:pkg.bzl", "pkg_tar")

            pkg_tar(
                name = "data",
                srcs = [
                    "bar.txt",
                    "foo.txt",
                ],
            )
            
            """.trimIndent()
            )

        val bazel: BuilderRunner = bazel()
        bazel.build("...")

        val dataTarPath: Path = context().resolveBinPath(bazel, "main/data.tar")
        Truth.assertThat(java.nio.file.Files.exists(dataTarPath)).isTrue()

        val directory: Path = decompress(dataTarPath)
        Truth.assertThat(directory.toFile().exists()).isTrue()

        val map: MutableMap<String?, Path?> =
            java.util.Arrays.stream<java.io.File?>(
                java.util.Objects.requireNonNull<Array<java.io.File?>?>(
                    directory.toFile().listFiles()
                )
            )
                .collect(
                    Collectors.toMap(
                        java.util.function.Function { obj: java.io.File? -> obj.getName() },
                        java.util.function.Function { file: java.io.File? -> Paths.get(file.getAbsolutePath()) })
                )

        WorkspaceTestUtils.assertLinesExactly(map.get("foo.txt"), "Hello World")
        WorkspaceTestUtils.assertLinesExactly(map.get("bar.txt"), "Hello World, again")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHttpArchive() {
        val repo: Path = context().getTmpDir().resolve("ext_repo")
        val generator: RepoWithRuleWritingTextGenerator = RepoWithRuleWritingTextGenerator(repo)
        generator.withOutputText(HELLO_FROM_EXTERNAL_REPOSITORY).setupRepository()

        // file where we will manually copy the built archive
        val zipFile: Path = context().getTmpDir().resolve("ext_repo.tar")
        Truth.assertThat(java.nio.file.Files.exists(zipFile)).isFalse()

        context()
            .write(
                "MODULE.bazel",
                "local_repository = use_repo_rule('@bazel_tools//tools/build_defs/repo:local.bzl',"
                        + " 'local_repository')",
                String.format(
                    "local_repository(name=\"ext_local\", path=\"%s\",)",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathForStarlarkFile(repo)
                ),
                "http_archive = use_repo_rule('@bazel_tools//tools/build_defs/repo:http.bzl',"
                        + " 'http_archive')",
                String.format(
                    "http_archive(name=\"ext\", urls=[\"%s\"],)",
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.pathToFileURI(zipFile)
                )
            )

        context()
            .write(
                "BUILD",
                RepoWithRuleWritingTextGenerator.Companion.loadRule("@ext"),
                RepoWithRuleWritingTextGenerator.Companion.callRule(
                    "call_from_main", "main_out.txt", HELLO_FROM_MAIN_REPOSITORY
                )
            )

        // first build the archive and copy it into zipFile
        val bazel: BuilderRunner = bazel()
        val tarTarget: String? = generator.getPkgTarTarget()
        bazel.build("@ext_local//:" + tarTarget)
        val packedFile: Path =
            context()
                .resolveBinPath(
                    bazel, String.format("external/+local_repository+ext_local/%s.tar", tarTarget)
                )
        java.nio.file.Files.copy(packedFile, zipFile)

        // now build the target from http_archive
        bazel.build("@ext//:" + RepoWithRuleWritingTextGenerator.Companion.TARGET)

        val xPath: Path = context().resolveBinPath(bazel, "external/+http_archive+ext/out")
        WorkspaceTestUtils.assertLinesExactly(xPath, HELLO_FROM_EXTERNAL_REPOSITORY)

        // and use the rule from http_archive in the main repository
        bazel.build("//:call_from_main")

        val mainOutPath: Path = context().resolveBinPath(bazel, "main_out.txt")
        WorkspaceTestUtils.assertLinesExactly(mainOutPath, HELLO_FROM_MAIN_REPOSITORY)
    }

    private fun bazel(): BuilderRunner {
        return WorkspaceTestUtils.bazel(context())
    }

    @Throws(java.lang.Exception::class)
    private fun decompress(dataTarPath: Path): Path {
        val fs: FileSystem = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()
        val dataTarPathForDecompress: Path =
            fs.getPath(dataTarPath.toAbsolutePath().toString())

        val directory: Path =
            TarFunction.INSTANCE.decompress(
                DecompressorDescriptor.builder()
                    .setArchivePath(dataTarPathForDecompress)
                    .setDestinationPath(dataTarPathForDecompress.getParentDirectory())
                    .build()
            )
        return Paths.get(directory.getPathString())
    } // TODO(ichern) test tar quoting

    companion object {
        private const val HELLO_FROM_EXTERNAL_REPOSITORY = "Hello from external repository!"
        private const val HELLO_FROM_MAIN_REPOSITORY = "Hello from main repository!"
    }
}
