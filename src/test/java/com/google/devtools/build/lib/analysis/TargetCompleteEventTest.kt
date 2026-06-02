// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInputMap

/** Tests for [TargetCompleteEvent].  */
@RunWith(JUnit4::class)
class TargetCompleteEventTest : AnalysisTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReferencedSourceFile() {
        scratch.file("BUILD", "filegroup(name = 'files', srcs = ['file'])")
        scratch.file("file", "content does not matter")
        val ctAndData: ConfiguredTargetAndData = getCtAndData("//:files")
        val artifactsToBuild: ArtifactsToBuild =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getArtifactsToBuild(ctAndData)
        val artifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(artifactsToBuild.getAllArtifacts().toList())
        val metadata: FileArtifactValue =
            FileArtifactValue.createForNormalFile(byteArrayOf(1, 2, 3), null, 10)
        val completionContext: CompletionContext =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getCompletionContext(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                    artifact,
                    metadata
                ), com.google.common.collect.ImmutableMap.of<SpecialArtifact?, TreeArtifactValue?>()
            )

        val event: TargetCompleteEvent =
            TargetCompleteEvent.successfulBuild(
                ctAndData,
                completionContext,
                artifactsToBuild.getAllArtifactsByOutputGroup(),  /* announceTargetSummary= */
                false
            )

        assertThat(event.referencedLocalFiles())
            .containsExactly(LocalFile(artifact.getPath(), LocalFileType.OUTPUT_FILE, metadata))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReferencedSourceDirectory() {
        scratch.file("BUILD", "filegroup(name = 'files', srcs = ['dir'])")
        scratch.file("dir/file", "content does not matter")
        val ctAndData: ConfiguredTargetAndData = getCtAndData("//:files")
        val artifactsToBuild: ArtifactsToBuild =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getArtifactsToBuild(ctAndData)
        val artifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(artifactsToBuild.getAllArtifacts().toList())
        val metadata: FileArtifactValue = FileArtifactValue.createForDirectoryWithMtime(0)
        val completionContext: CompletionContext =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getCompletionContext(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                    artifact,
                    metadata
                ), com.google.common.collect.ImmutableMap.of<SpecialArtifact?, TreeArtifactValue?>()
            )

        val event: TargetCompleteEvent =
            TargetCompleteEvent.successfulBuild(
                ctAndData,
                completionContext,
                artifactsToBuild.getAllArtifactsByOutputGroup(),  /* announceTargetSummary= */
                false
            )

        assertThat(event.referencedLocalFiles())
            .containsExactly(
                LocalFile(artifact.getPath(), LocalFileType.OUTPUT_DIRECTORY, metadata)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReferencedTreeArtifact() {
        scratch.file(
            "defs.bzl",
            """
        def _impl(ctx):
            d = ctx.actions.declare_directory(ctx.label.name)
            ctx.actions.run_shell(outputs = [d], command = "does not matter")
            return DefaultInfo(files = depset([d]))

        dir = rule(_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':defs.bzl', 'dir')",
            "dir(name = 'dir')",
            "filegroup(name = 'files', srcs = ['dir'])"
        )
        val ctAndData: ConfiguredTargetAndData = getCtAndData("//:files")
        val artifactsToBuild: ArtifactsToBuild =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getArtifactsToBuild(ctAndData)
        val tree: SpecialArtifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                artifactsToBuild.getAllArtifacts().toList()
            ) as SpecialArtifact?
        val fileChild: TreeFileArtifact =
            TreeFileArtifact.createTreeOutput(tree, PathFragment.create("dir/file.txt"))
        val fileMetadata: FileArtifactValue? =
            FileArtifactValue.createForNormalFile(byteArrayOf(1, 2, 3), null, 10)
        // A TreeFileArtifact can be a directory, when materialized by a symlink.
        // See https://github.com/bazelbuild/bazel/issues/20418.
        val dirChild: TreeFileArtifact = TreeFileArtifact.createTreeOutput(tree, PathFragment.create("sym"))
        val dirMetadata: FileArtifactValue? = FileArtifactValue.createForDirectoryWithMtime(123456789)
        val metadata: TreeArtifactValue =
            TreeArtifactValue.newBuilder(tree)
                .putChild(fileChild, fileMetadata)
                .putChild(dirChild, dirMetadata)
                .build()
        val completionContext: CompletionContext =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getCompletionContext(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(),
                com.google.common.collect.ImmutableMap.of<SpecialArtifact?, TreeArtifactValue?>(tree, metadata)
            )

        val event: TargetCompleteEvent =
            TargetCompleteEvent.successfulBuild(
                ctAndData,
                completionContext,
                artifactsToBuild.getAllArtifactsByOutputGroup(),  /* announceTargetSummary= */
                false
            )

        assertThat(event.referencedLocalFiles())
            .containsExactly(
                LocalFile(fileChild.getPath(), LocalFileType.OUTPUT_FILE, fileMetadata),
                LocalFile(dirChild.getPath(), LocalFileType.OUTPUT_DIRECTORY, dirMetadata)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReferencedUnresolvedSymlink() {
        scratch.file(
            "defs.bzl",
            """
        def _impl(ctx):
            s = ctx.actions.declare_symlink(ctx.label.name)
            ctx.actions.symlink(output = s, target_path = "does not matter")
            return DefaultInfo(files = depset([s]))

        sym = rule(_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':defs.bzl', 'sym')",
            "sym(name = 'sym')",
            "filegroup(name = 'files', srcs = ['sym'])"
        )
        val ctAndData: ConfiguredTargetAndData = getCtAndData("//:files")
        val artifactsToBuild: ArtifactsToBuild =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getArtifactsToBuild(ctAndData)
        val artifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(artifactsToBuild.getAllArtifacts().toList())
        artifact.getPath().getParentDirectory().createDirectoryAndParents()
        artifact.getPath().createSymbolicLink(fileSystem.getPath("/some/path"))
        val metadata: FileArtifactValue = FileArtifactValue.createForUnresolvedSymlink(artifact.getPath())
        val completionContext: CompletionContext =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getCompletionContext(
                com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(
                    artifact,
                    metadata
                ), com.google.common.collect.ImmutableMap.of<SpecialArtifact?, TreeArtifactValue?>()
            )

        val event: TargetCompleteEvent =
            TargetCompleteEvent.successfulBuild(
                ctAndData,
                completionContext,
                artifactsToBuild.getAllArtifactsByOutputGroup(),  /* announceTargetSummary= */
                false
            )

        assertThat(event.referencedLocalFiles())
            .containsExactly(LocalFile(artifact.getPath(), LocalFileType.OUTPUT_SYMLINK, metadata))
    }

    /** Regression test for b/165671166.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileProtoFromArtifactReencodesAsUtf8() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            // Windows filesystems return paths with wide characters and don't suffer from the current
            // workaround where arbitrary bytes are represented to Java as Latin-1.
            return
        }
        scratch.file("sh/BUILD", "filegroup(name = 'globby', srcs = glob(['dir/*']))")
        // Bytes are UTF-8 encoding of: sh/dir/圖片
        val filenameBytes = byteArrayOf(
            0x73, 0x68, 0x2f, 0x64, 0x69, 0x72, 0x2f, -27, -100, -106, -25, -119, -121
        )
        val utf8InLatin1FileName = String(filenameBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
        scratch.file(utf8InLatin1FileName, "content does not matter")
        val ctAndData: ConfiguredTargetAndData = getCtAndData("//sh:globby")
        val artifactsToBuild: ArtifactsToBuild =
            com.google.devtools.build.lib.analysis.TargetCompleteEventTest.Companion.getArtifactsToBuild(ctAndData)
        val artifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(artifactsToBuild.getAllArtifacts().toList())
        val metadata: FileArtifactValue? =
            FileArtifactValue.createForNormalFile(byteArrayOf(1, 2, 3), null, 10)

        val fileProto: File = TargetCompleteEvent.newFile(artifact, metadata)

        // Bytes are the same but the encoding is actually UTF-8 as required of a protobuf string.
        assertThat(fileProto.getName()).isEqualTo(String(filenameBytes, java.nio.charset.StandardCharsets.UTF_8))
    }

    @Throws(java.lang.Exception::class)
    private fun getCtAndData(target: String?): ConfiguredTargetAndData {
        val result: AnalysisResult = update(target)
        val ct: ConfiguredTarget? = com.google.common.collect.Iterables.getOnlyElement<T?>(result.getTargetsToBuild())
        val tac: TargetAndConfiguration? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getTopLevelTargetsWithConfigs())
        val configuredTargetConfiguration: BuildConfigurationValue? =
            skyframeExecutor.getEvaluator().getExistingValue(ct.getConfigurationKey()) as BuildConfigurationValue?
        return ConfiguredTargetAndData(ct, tac.getTarget(), configuredTargetConfiguration, null)
    }

    companion object {
        private fun getArtifactsToBuild(ctAndData: ConfiguredTargetAndData): ArtifactsToBuild {
            val context: TopLevelArtifactContext =
                TopLevelArtifactContext(false, false, OutputGroupInfo.DEFAULT_GROUPS)
            return TopLevelArtifactHelper.getAllArtifactsToBuild(ctAndData.getConfiguredTarget(), context)
        }

        private fun getCompletionContext(
            metadata: MutableMap<Artifact?, FileArtifactValue?>,
            treeMetadata: MutableMap<SpecialArtifact?, TreeArtifactValue?>
        ): CompletionContext {
            val inputMap: ActionInputMap = ActionInputMap(0)
            metadata.forEach(inputMap::put)
            treeMetadata.forEach(inputMap::putTreeArtifact)
            return CompletionContext(
                ArtifactPathResolver.IDENTITY, inputMap,  /* expandFilesets= */false
            )
        }
    }
}
