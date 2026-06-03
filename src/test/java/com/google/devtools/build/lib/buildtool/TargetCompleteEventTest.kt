// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.Artifact

/** Verifies TargetCompleteEvent behavior during a complete build.  */
@RunWith(JUnit4::class)
class TargetCompleteEventTest : BuildIntegrationTestCase() {
    @org.junit.Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    @Before
    @Throws(java.lang.Exception::class)
    fun stageEmbeddedTools() {
        AnalysisMock.get().setupMockToolsRepository(mockToolsConfig)
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(NoSpawnCacheModule())
            .addBlazeModule(CredentialModule())
            .addBlazeModule(BazelBuildEventServiceModule())

    @Throws(java.lang.Exception::class)
    private fun afterBuildCommand() {
        runtimeWrapper.newCommand()
    }

    /**
     * Validates that TargetCompleteEvents do not keep a map of action output metadata for the
     * _validation output group, which can be quite large.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun artifactsNotRetained() {
        write(
            "validation_actions/defs.bzl",
            """
        def _rule_with_implicit_outs_and_validation_impl(ctx):
            ctx.actions.write(ctx.outputs.main, "main output\
            ")

            ctx.actions.write(ctx.outputs.implicit, "implicit output\

            ")

            validation_output = ctx.actions.declare_file(ctx.attr.name + ".validation")

            # The actual tool will be created in individual tests, depending on whether
            # validation should pass or fail.
            ctx.actions.run(
                outputs = [validation_output],
                executable = ctx.executable._validation_tool,
                arguments = [validation_output.path],
            )

            return [
                DefaultInfo(files = depset([ctx.outputs.main])),
                OutputGroupInfo(_validation = depset([validation_output])),
            ]

        rule_with_implicit_outs_and_validation = rule(
            implementation = _rule_with_implicit_outs_and_validation_impl,
            outputs = {
                "main": "%{name}.main",
                "implicit": "%{name}.implicit",
            },
            attrs = {
                "_validation_tool": attr.label(
                    allow_single_file = True,
                    default = Label("//validation_actions:validation_tool"),
                    executable = True,
                    cfg = "exec",
                ),
            },
        )
        
        """.trimIndent()
        )
        write("validation_actions/validation_tool", "#!/bin/bash", "echo \"validation output\" > $1")
            .setExecutable(true)
        write(
            "validation_actions/BUILD",
            """
        load(
            ":defs.bzl",
            "rule_with_implicit_outs_and_validation",
        )

        rule_with_implicit_outs_and_validation(name = "foo0")
        
        """.trimIndent()
        )

        val targetCompleteEventRef: AtomicReference<TargetCompleteEvent?> = AtomicReference<TargetCompleteEvent?>()
        runtimeWrapper.registerSubscriber(
            object : Any() {
                @Suppress("unused")
                @com.google.common.eventbus.Subscribe
                fun accept(event: TargetCompleteEvent?) {
                    targetCompleteEventRef.set(event)
                }
            })

        addOptions("--run_validations")
        val buildResult: BuildResult = buildTarget("//validation_actions:foo0")

        val successfulTargets: MutableCollection<ConfiguredTarget?> = buildResult.getSuccessfulTargets()
        val fooTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTarget?>(successfulTargets)

        // Check that the primary output, :foo0.main, has its metadata retained.
        val main: Artifact? =
            (fooTarget as RuleConfiguredTarget)
                .findArtifactByOutputLabel(
                    Label.parseCanonicalUnchecked("//validation_actions:foo0.main")
                )
        val mainMetadata: FileArtifactValue? =
            targetCompleteEventRef.get().getCompletionContext().getFileArtifactValue(main)
        assertThat(mainMetadata).isNotNull()

        // Check that the validation output, :foo0.validation, does not have its metadata retained.
        val outputGroups: OutputGroupInfo = fooTarget.get(OutputGroupInfo.STARLARK_CONSTRUCTOR)
        val validationArtifacts: NestedSet<Artifact?> =
            outputGroups.getOutputGroup(OutputGroupInfo.VALIDATION)
        assertThat(validationArtifacts.isEmpty()).isFalse()

        val validationArtifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(validationArtifacts.toList())

        val validationArtifactMetadata: FileArtifactValue? =
            targetCompleteEventRef
                .get()
                .getCompletionContext()
                .getFileArtifactValue(validationArtifact)
        assertThat(validationArtifactMetadata).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFile() {
        write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'echo -n Hello > $@')")

        addOptions("--experimental_build_event_output_group_mode=default=named_set_of_files_only")
        val bep: java.io.File = buildTargetAndCaptureBEP("//foo:foobin")

        val outFile: BuildEventStreamProtos.File? = findOutputFileInBEPStream(bep, "out.txt")
        assertThat(outFile).isNotNull()
        assertThat(outFile.getUri()).startsWith("file://")
        assertThat(outFile.getUri()).endsWith("/bin/foo/out.txt")
        assertThat(outFile.getLength()).isEqualTo("Hello".length)
        assertDigest("Hello", com.google.common.io.BaseEncoding.base16().lowerCase().decode(outFile.getDigest()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputDirectory() {
        write(
            "foo/defs.bzl",
            """
        def _impl(ctx):
            dir = ctx.actions.declare_directory(ctx.label.name)
            ctx.actions.run_shell(
                outputs = [dir],
                command = "echo -n Hello > %s/file.txt" % dir.path,
            )
            return DefaultInfo(files = depset([dir]))

        directory = rule(implementation = _impl)
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "directory")

        directory(name = "dir")
        
        """.trimIndent()
        )

        addOptions("--experimental_build_event_output_group_mode=default=named_set_of_files_only")
        val bep: java.io.File = buildTargetAndCaptureBEP("//foo:dir")

        val targetComplete: BuildEventStreamProtos.TargetComplete? = findTargetCompleteEventInBEPStream(bep)
        assertThat(targetComplete.getDirectoryOutputList()).hasSize(1)
        val dir: BuildEventStreamProtos.File = targetComplete.getDirectoryOutputList().get(0)
        assertThat(dir.getName()).endsWith("/dir")
        assertThat(dir.getUri()).isEmpty()
        assertThat(dir.getContents()).isEmpty()
        assertThat(dir.getSymlinkTargetPath()).isEmpty()

        val outFile: BuildEventStreamProtos.File? = findOutputFileInBEPStream(bep, "file.txt")
        assertThat(outFile).isNotNull()
        assertThat(outFile.getUri()).startsWith("file://")
        assertThat(outFile.getUri()).endsWith("/bin/foo/dir/file.txt")
        assertThat(outFile.getLength()).isEqualTo("Hello".length)
        assertDigest("Hello", com.google.common.io.BaseEncoding.base16().lowerCase().decode(outFile.getDigest()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputSymlink() {
        write(
            "foo/defs.bzl",
            """
        def _impl(ctx):
            sym = ctx.actions.declare_symlink(ctx.label.name)
            ctx.actions.symlink(output = sym, target_path = "/some/path")
            return DefaultInfo(files = depset([sym]))

        symlink = rule(implementation = _impl)
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "symlink")

        symlink(name = "sym")
        
        """.trimIndent()
        )

        addOptions("--experimental_build_event_output_group_mode=default=named_set_of_files_only")
        val bep: java.io.File = buildTargetAndCaptureBEP("//foo:sym")

        val outFile: BuildEventStreamProtos.File? = findOutputFileInBEPStream(bep, "sym")
        assertThat(outFile).isNotNull()
        assertThat(outFile.getSymlinkTargetPath()).isEqualTo("/some/path")
        assertThat(outFile.getLength()).isEqualTo(0)
        assertThat(outFile.getDigest()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFile_inlineOutputGroup() {
        write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'echo -n Hello > $@')")

        addOptions("--experimental_build_event_output_group_mode=default=inline_only")
        val bep: java.io.File = buildTargetAndCaptureBEP("//foo:foobin")

        val outFileFromNestedSet: BuildEventStreamProtos.File? = findOutputFileInBEPStream(bep, "out.txt")
        assertThat(outFileFromNestedSet).isNull()

        val completeEvent: TargetComplete? = findTargetCompleteEventInBEPStream(bep)
        assertThat(completeEvent).isNotNull()
        assertThat(completeEvent.getOutputGroupCount()).isEqualTo(1)
        assertThat(completeEvent.getOutputGroup(0).getInlineFilesCount()).isEqualTo(1)

        val outFile: BuildEventStreamProtos.File = completeEvent.getOutputGroup(0).getInlineFiles(0)
        assertThat(outFile.getUri()).startsWith("file://")
        assertThat(outFile.getUri()).endsWith("/bin/foo/out.txt")
        assertThat(outFile.getLength()).isEqualTo("Hello".length)
        assertDigest("Hello", com.google.common.io.BaseEncoding.base16().lowerCase().decode(outFile.getDigest()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFile_outputGroupFileModeOptionRepeated_lastValueTaken() {
        write("foo/BUILD", "genrule(name = 'foobin', outs = ['out.txt'], cmd = 'echo -n Hello > $@')")

        addOptions("--experimental_build_event_output_group_mode=default=named_set_of_files_only")
        addOptions("--experimental_build_event_output_group_mode=default=inline_only")
        val bep: java.io.File = buildTargetAndCaptureBEP("//foo:foobin")

        val outFileFromNestedSet: BuildEventStreamProtos.File? = findOutputFileInBEPStream(bep, "out.txt")
        assertThat(outFileFromNestedSet).isNull()

        val completeEvent: TargetComplete? = findTargetCompleteEventInBEPStream(bep)
        assertThat(completeEvent).isNotNull()
        assertThat(completeEvent.getOutputGroupCount()).isEqualTo(1)
        assertThat(completeEvent.getOutputGroup(0).getInlineFilesCount()).isEqualTo(1)
        val outFile: BuildEventStreamProtos.File = completeEvent.getOutputGroup(0).getInlineFiles(0)
        assertDigest("Hello", com.google.common.io.BaseEncoding.base16().lowerCase().decode(outFile.getDigest()))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputFile_multipleOutputGroups() {
        write(
            "foo/defs.bzl",
            """
        def _impl(ctx):
            inline_out = ctx.actions.declare_file(ctx.label.name + '.inline.txt')
            ctx.actions.write(output = inline_out, content = 'Hello')
            fileset_out = ctx.actions.declare_file(ctx.label.name + '.fileset.txt')
            ctx.actions.write(output = fileset_out, content = 'Hola')
            both_out = ctx.actions.declare_file(ctx.label.name + '.both.txt')
            ctx.actions.write(output = both_out, content = 'Bonjour')
            output_groups = {
                "inlinegroup": depset([inline_out]),
                "filesetgroup": depset([fileset_out]),
                "bothgroup": depset([both_out]),
            }
            return [
                OutputGroupInfo(**output_groups),
            ]

        multiple_groups = rule(implementation = _impl)
        
        """.trimIndent()
        )
        write(
            "foo/BUILD",
            """
        load(":defs.bzl", "multiple_groups")

        multiple_groups(name = "myrule")
        
        """.trimIndent()
        )

        addOptions("--experimental_build_event_output_group_mode=inlinegroup=inline_only")
        addOptions("--experimental_build_event_output_group_mode=filesetgroup=named_set_of_files_only")
        addOptions("--experimental_build_event_output_group_mode=bothgroup=both")
        addOptions("--output_groups=+inlinegroup,+filesetgroup,+bothgroup")
        val bep: java.io.File = buildTargetAndCaptureBEP("//foo:myrule")

        val completeEvent: TargetComplete? = findTargetCompleteEventInBEPStream(bep)
        assertThat(completeEvent).isNotNull()
        assertThat(completeEvent.getOutputGroupCount()).isEqualTo(3)
        val inlineOutputGroup: OutputGroup = findOutputGroupWithName(completeEvent, "inlinegroup")
        val filesetOutputGroup: OutputGroup = findOutputGroupWithName(completeEvent, "filesetgroup")
        val bothOutputGroup: OutputGroup = findOutputGroupWithName(completeEvent, "bothgroup")

        assertThat(inlineOutputGroup.getInlineFilesCount()).isEqualTo(1)
        assertThat(findOutputFileInBEPStream(bep, "myrule.inline.txt")).isNull()
        val inlineOutFile: BuildEventStreamProtos.File = inlineOutputGroup.getInlineFiles(0)
        assertThat(inlineOutFile.getUri()).startsWith("file://")
        assertThat(inlineOutFile.getUri()).endsWith("/bin/foo/myrule.inline.txt")
        assertThat(inlineOutFile.getLength()).isEqualTo("Hello".length)
        assertDigest("Hello", com.google.common.io.BaseEncoding.base16().lowerCase().decode(inlineOutFile.getDigest()))

        assertThat(filesetOutputGroup.getInlineFilesCount()).isEqualTo(0)
        val filesetOutFile: BuildEventStreamProtos.File? =
            findOutputFileInBEPStream(bep, "myrule.fileset.txt")
        assertThat(filesetOutFile.getUri()).startsWith("file://")
        assertThat(filesetOutFile.getUri()).endsWith("/bin/foo/myrule.fileset.txt")
        assertThat(filesetOutFile.getLength()).isEqualTo("Hola".length)
        assertDigest("Hola", com.google.common.io.BaseEncoding.base16().lowerCase().decode(filesetOutFile.getDigest()))

        assertThat(bothOutputGroup.getInlineFilesCount()).isEqualTo(1)
        val bothOutFileInline: BuildEventStreamProtos.File? = bothOutputGroup.getInlineFiles(0)
        val bothOutFileInFileset: BuildEventStreamProtos.File? =
            findOutputFileInBEPStream(bep, "myrule.both.txt")
        for (outfile in com.google.common.collect.ImmutableList.of<Any?>(bothOutFileInline, bothOutFileInFileset)) {
            assertThat(outfile.getUri()).startsWith("file://")
            assertThat(outfile.getUri()).endsWith("/bin/foo/myrule.both.txt")
            assertThat(outfile.getLength()).isEqualTo("Bonjour".length)
            assertDigest("Bonjour", com.google.common.io.BaseEncoding.base16().lowerCase().decode(outfile.getDigest()))
        }
    }

    @Throws(java.lang.Exception::class)
    private fun buildTargetAndCaptureBEP(target: String?): java.io.File {
        val bep: java.io.File = tmpFolder.newFile()
        // We use WAIT_FOR_UPLOAD_COMPLETE because it's the easiest way to force the BES module to
        // wait until the BEP binary file has been written.
        addOptions(
            "--build_event_binary_file=" + bep.getAbsolutePath(),
            "--bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE"
        )
        buildTarget(target)
        // We need to wait for all events to be written to the file, which is done in #afterCommand()
        // if --bes_upload_mode=WAIT_FOR_UPLOAD_COMPLETE.
        afterBuildCommand()
        return bep
    }

    private fun assertDigest(contents: String?, bepDigest: ByteArray?) {
        assertThat(
            fileSystem.getDigestFunction().getHashFunction()
                .hashString(contents, java.nio.charset.StandardCharsets.UTF_8).asBytes()
        )
            .isEqualTo(bepDigest)
    }

    companion object {
        private fun findOutputGroupWithName(
            completeEvent: TargetComplete, bothgroup: String?
        ): OutputGroup {
            return completeEvent.getOutputGroupList().stream()
                .filter({ og -> og.getName().equals(bothgroup) })
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        }

        @Throws(IOException::class)
        private fun parseBuildEventsFromBEPStream(bep: java.io.File): com.google.common.collect.ImmutableList<BuildEvent> {
            val buildEvents: com.google.common.collect.ImmutableList.Builder<BuildEvent?> =
                com.google.common.collect.ImmutableList.builder<BuildEvent?>()
            FileInputStream(bep).use { `in` ->
                var ev: BuildEvent?
                while ((BuildEvent.parseDelimitedFrom(`in`).also { ev = it }) != null) {
                    buildEvents.add(ev)
                }
            }
            return buildEvents.build()
        }

        @Throws(IOException::class)
        private fun findTargetCompleteEventInBEPStream(bep: java.io.File): BuildEventStreamProtos.TargetComplete? {
            for (buildEvent in parseBuildEventsFromBEPStream(bep)) {
                if (buildEvent.getId().getIdCase() === IdCase.TARGET_COMPLETED) {
                    return buildEvent.getCompleted()
                }
            }
            return null
        }

        @Throws(IOException::class)
        private fun findOutputFileInBEPStream(bep: java.io.File, name: String?): BuildEventStreamProtos.File? {
            for (buildEvent in parseBuildEventsFromBEPStream(bep)) {
                if (buildEvent.getId().getIdCase() === IdCase.NAMED_SET) {
                    val namedSetOfFiles: NamedSetOfFiles = buildEvent.getNamedSetOfFiles()
                    for (file in namedSetOfFiles.getFilesList()) {
                        if (file.getName().contains(name)) {
                            return file
                        }
                    }
                }
            }
            return null
        }
    }
}
