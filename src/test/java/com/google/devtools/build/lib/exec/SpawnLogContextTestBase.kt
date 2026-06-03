// Copyright 2023 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.exec.SpawnLogContext.millisToProto
import com.google.devtools.build.lib.exec.SpawnLogContextTestBase.Companion.createInputMap
import com.google.devtools.build.lib.exec.SpawnLogContextTestBase.Companion.createInputMetadataProvider
import com.google.devtools.build.lib.exec.SpawnLogContextTestBase.Companion.createRunfilesTree
import com.google.devtools.build.lib.exec.SpawnLogContextTestBase.Companion.writeFile

/** Base class for [SpawnLogContext] tests.  */
@RunWith(TestParameterInjector::class)
abstract class SpawnLogContextTestBase {
    protected val digestHashFunction: DigestHashFunction = DigestHashFunction.SHA256
    protected val fs: FileSystem = InMemoryFileSystem(digestHashFunction)
    protected val outputBase: Path = fs.getPath("/home/user/bazel/output_base")
    protected val externalRoot: Path = outputBase.getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
    protected val externalRepo: RepositoryName = RepositoryName.createUnvalidated("some_repo")

    protected var outputDir: ArtifactRoot? = null
    protected var execRoot: Path? = null
    protected var rootDir: ArtifactRoot? = null
    protected var externalSourceRoot: ArtifactRoot? = null
    protected var externalOutputDir: ArtifactRoot? = null
    protected var configuration: BuildConfigurationValue? = null
    protected var storedEventHandler: StoredEventHandler? = null

    @TestParameter
    var siblingRepositoryLayout: Boolean = false

    @Before
    @Throws(InvalidConfigurationException::class, OptionsParsingException::class)
    fun setup() {
        val defaultBuildOptions: BuildOptions? =
            BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java))
        configuration =
            BuildConfigurationValue.createForTesting(
                defaultBuildOptions,
                "k8-fastbuild",
                siblingRepositoryLayout,
                BlazeDirectories(
                    ServerDirectories(outputBase, outputBase, outputBase),  /* workspace= */
                    null,
                    TestConstants.PRODUCT_NAME
                ),
                object : GlobalStateProvider() {
                    public override fun getActionEnvironment(buildOptions: BuildOptions?): ActionEnvironment {
                        return ActionEnvironment.EMPTY
                    }

                    val fragmentRegistry: FragmentRegistry
                        get() = FragmentRegistry.create(
                            com.google.common.collect.ImmutableList.of<E?>(),
                            com.google.common.collect.ImmutableList.of<E?>(),
                            com.google.common.collect.ImmutableList.of<E?>()
                        )

                    val reservedActionMnemonics: com.google.common.collect.ImmutableSet<String?>
                        get() = com.google.common.collect.ImmutableSet.of<String?>()

                    val runfilesPrefix: String
                        get() = TestConstants.WORKSPACE_NAME
                },
                FragmentFactory()
            )
        outputDir = configuration.getBinDirectory(RepositoryName.MAIN)
        execRoot = configuration.getDirectories().getExecRoot(TestConstants.WORKSPACE_NAME)
        rootDir = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot))

        externalSourceRoot =
            ArtifactRoot.asExternalSourceRoot(
                Root.fromPath(externalRoot.getChild(externalRepo.name))
            )
        externalOutputDir = configuration.getBinDirectory(externalRepo)
        storedEventHandler = StoredEventHandler()
    }

    // A fake action filesystem that provides a fast digest, but refuses to compute it from the
    // file contents (which won't be available when building without the bytes).
    protected class FakeActionFileSystem internal constructor(delegateFs: FileSystem?) :
        DelegateFileSystem(delegateFs) {
        @Throws(IOException::class)
        public override fun getFastDigest(path: PathFragment?): ByteArray {
            return super.getDigest(path)
        }

        @Throws(IOException::class)
        public override fun getDigest(path: PathFragment?): ByteArray? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    /** Test parameter determining whether the spawn inputs are also tool inputs.  */
    protected enum class InputsMode {
        TOOLS,
        NON_TOOLS;

        val isTool: Boolean
            get() = this == InputsMode.TOOLS
    }

    /** Test parameter determining whether to emulate building with or without the bytes.  */
    protected enum class OutputsMode {
        WITH_BYTES,
        WITHOUT_BYTES;

        fun getActionFileSystem(fs: FileSystem?): FileSystem? {
            return if (this == OutputsMode.WITHOUT_BYTES) FakeActionFileSystem(fs) else fs
        }
    }

    /** Test parameter determining whether an input/output directory should be empty.  */
    internal enum class DirContents {
        EMPTY,
        NON_EMPTY;

        val isEmpty: Boolean
            get() = this == DirContents.EMPTY
    }

    /** Test parameter determining whether an output is indirected through a symlink.  */
    internal enum class OutputIndirection {
        DIRECT,
        INDIRECT;

        fun viaSymlink(): Boolean {
            return this == OutputIndirection.INDIRECT
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileInput(@TestParameter inputsMode: InputsMode) {
        val fileInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "file")

        Companion.writeFile(fileInput, "abc")

        val spawn: SpawnBuilder = defaultSpawnBuilder().withInputs(fileInput)
        if (inputsMode.isTool) {
            spawn.withTools(fileInput)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            Companion.createInputMetadataProvider(fileInput),
            createInputMap(fileInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath("file")
                        .setDigest(getDigest("abc"))
                        .setIsTool(inputsMode.isTool)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileInputWithDirectoryContents(
        @TestParameter inputsMode: InputsMode, @TestParameter dirContents: DirContents
    ) {
        val fileInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "file")

        fileInput.getPath().createDirectoryAndParents()
        if (!dirContents.isEmpty) {
            writeFile(fileInput.getPath().getChild("file"), "abc")
        }

        val spawn: SpawnBuilder = defaultSpawnBuilder().withInputs(fileInput)
        if (inputsMode.isTool) {
            spawn.withTools(fileInput)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            Companion.createInputMetadataProvider(fileInput),
            createInputMap(fileInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addAllInputs(
                    if (dirContents.isEmpty)
                        com.google.common.collect.ImmutableList.of<E?>()
                    else
                        com.google.common.collect.ImmutableList.of<E?>(
                            File.newBuilder()
                                .setPath("file/file")
                                .setDigest(getDigest("abc"))
                                .setIsTool(inputsMode.isTool)
                                .build()
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryInput(
        @TestParameter inputsMode: InputsMode, @TestParameter dirContents: DirContents
    ) {
        val dirInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "dir")

        dirInput.getPath().createDirectoryAndParents()
        if (!dirContents.isEmpty) {
            writeFile(dirInput.getPath().getChild("file"), "abc")
        }

        val spawn: SpawnBuilder = defaultSpawnBuilder().withInputs(dirInput)
        if (inputsMode == InputsMode.TOOLS) {
            spawn.withTools(dirInput)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            Companion.createInputMetadataProvider(dirInput),
            createInputMap(dirInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addAllInputs(
                    if (dirContents.isEmpty)
                        com.google.common.collect.ImmutableList.of<E?>()
                    else
                        com.google.common.collect.ImmutableList.of<E?>(
                            File.newBuilder()
                                .setPath("dir/file")
                                .setDigest(getDigest("abc"))
                                .setIsTool(inputsMode.isTool)
                                .build()
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeInput(
        @TestParameter inputsMode: InputsMode, @TestParameter dirContents: DirContents
    ) {
        val treeInput: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree")

        treeInput.getPath().createDirectoryAndParents()
        if (!dirContents.isEmpty) {
            writeFile(treeInput.getPath().getChild("child"), "abc")
        }

        val spawn: SpawnBuilder = defaultSpawnBuilder().withInputs(treeInput)
        if (inputsMode.isTool) {
            spawn.withTools(treeInput)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            createInputMetadataProvider(treeInput),
            createInputMap(treeInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addAllInputs(
                    if (dirContents.isEmpty)
                        com.google.common.collect.ImmutableList.of<E?>()
                    else
                        com.google.common.collect.ImmutableList.of<E?>(
                            File.newBuilder()
                                .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tree/child")
                                .setDigest(getDigest("abc"))
                                .setIsTool(inputsMode.isTool)
                                .build()
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnresolvedSymlinkInput(@TestParameter inputsMode: InputsMode) {
        val symlinkInput: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink")

        symlinkInput.getPath().getParentDirectory().createDirectoryAndParents()
        symlinkInput.getPath().createSymbolicLink(PathFragment.create("/some/path"))

        val spawn: SpawnBuilder = defaultSpawnBuilder().withInputs(symlinkInput)
        if (inputsMode.isTool) {
            spawn.withTools(symlinkInput)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            Companion.createInputMetadataProvider(symlinkInput),
            createInputMap(symlinkInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
                        .setSymlinkTargetPath("/some/path")
                        .setIsTool(inputsMode.isTool)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesFileInput(@TestParameter inputsMode: InputsMode) {
        val runfilesInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "data.txt")
        val runfilesArtifact: Artifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "foo.runfiles")

        Companion.writeFile(runfilesInput, "abc")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("foo.runfiles")
        val runfilesTree: RunfilesTree? = createRunfilesTree(runfilesRoot, runfilesInput)

        val spawnBuilder: SpawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact)
        if (inputsMode.isTool) {
            spawnBuilder.withTool(runfilesArtifact)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawnBuilder.build(),
            Companion.createInputMetadataProvider(runfilesTree, runfilesArtifact, runfilesInput),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/data.txt")
                        )
                        .setDigest(getDigest("abc"))
                        .setIsTool(inputsMode.isTool)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolInRunfilesTree() {
        val runfilesTreeArtifact: Artifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "runfiles")
        val runfilesInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "data.txt")
        Companion.writeFile(runfilesInput, "abc")
        val toolFile1: Artifact = ActionsTestUtil.createArtifact(rootDir, "tool1")
        Companion.writeFile(toolFile1, "def")
        val toolFile2: Artifact = ActionsTestUtil.createArtifact(rootDir, "tool2")
        Companion.writeFile(toolFile2, "ghi")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("foo.runfiles")
        val runfilesTree: RunfilesTree? = createRunfilesTree(runfilesRoot, runfilesInput)

        val tools: NestedSet<ActionInput?>? =
            NestedSetBuilder.< ActionInput > stableOrder < ActionInput ? > ()
                .add(toolFile1)
                .addTransitive(
                    NestedSetBuilder.< ActionInput > stableOrder < ActionInput ? > ()
                        .add(runfilesTreeArtifact)
                        .add(toolFile2)
                        .build()
                )
                .build()
        val spawn: Spawn = defaultSpawnBuilder().withInputs(tools).withTools(tools).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree, runfilesTreeArtifact, runfilesInput, toolFile1, toolFile2
            ),
            createInputMap(runfilesTree, toolFile1, toolFile2),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/data.txt")
                        )
                        .setDigest(getDigest("abc"))
                        .setIsTool(true)
                )
                .addInputs(
                    File.newBuilder().setPath("tool1").setDigest(getDigest("def")).setIsTool(true)
                )
                .addInputs(
                    File.newBuilder().setPath("tool2").setDigest(getDigest("ghi")).setIsTool(true)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesDirectoryInput(
        @TestParameter dirContents: DirContents, @TestParameter inputsMode: InputsMode
    ) {
        val runfilesArtifact: Artifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "runfiles")
        val runfilesInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "dir")

        runfilesInput.getPath().createDirectoryAndParents()
        if (!dirContents.isEmpty) {
            writeFile(runfilesInput.getPath().getChild("data.txt"), "abc")
        }

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("foo.runfiles")
        val runfilesTree: RunfilesTree? = createRunfilesTree(runfilesRoot, runfilesInput)

        val spawnBuilder: SpawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact)
        if (inputsMode.isTool) {
            spawnBuilder.withTool(runfilesArtifact)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawnBuilder.build(),
            Companion.createInputMetadataProvider(runfilesTree, runfilesArtifact, runfilesInput),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addAllInputs(
                    if (dirContents.isEmpty)
                        com.google.common.collect.ImmutableList.of<E?>()
                    else
                        com.google.common.collect.ImmutableList.of<E?>(
                            File.newBuilder()
                                .setPath(
                                    (TestConstants.PRODUCT_NAME
                                            + "-out/k8-fastbuild/bin/foo.runfiles/"
                                            + TestConstants.WORKSPACE_NAME
                                            + "/dir/data.txt")
                                )
                                .setDigest(getDigest("abc"))
                                .setIsTool(inputsMode.isTool)
                                .build()
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesEmptyInput(@TestParameter inputsMode: InputsMode) {
        val runfilesArtifact: Artifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "runfiles")

        val runfilesInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "sub/dir/script.py")
        Companion.writeFile(runfilesInput, "abc")
        val someRepoPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("pkg"))
        val externalSourceArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalSourceRoot,
                someRepoPkg.getExecPath(siblingRepositoryLayout).getChild("lib.py").getPathString()
            )
        Companion.writeFile(externalSourceArtifact, "external_source")
        val someRepoOtherPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"))
        val externalGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalOutputDir,
                someRepoOtherPkg
                    .getPackagePath(siblingRepositoryLayout)
                    .getChild("gen.py")
                    .getPathString()
            )
        Companion.writeFile(externalGenArtifact, "external_gen")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot, runfilesInput, externalGenArtifact, externalSourceArtifact
            )

        val spawnBuilder: SpawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact)
        if (inputsMode.isTool) {
            spawnBuilder.withTool(runfilesArtifact)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawnBuilder.build(),
            Companion.createInputMetadataProvider(
                runfilesTree,
                runfilesArtifact,
                runfilesInput,
                externalGenArtifact,
                externalSourceArtifact
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/foo.runfiles/__init__.py")
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/sub/__init__.py")
                        )
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/sub/dir/__init__.py")
                        )
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/sub/dir/script.py")
                        )
                        .setDigest(getDigest("abc"))
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/__init__.py"
                        )
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/other/__init__.py"
                        )
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/other/pkg/__init__.py"
                        )
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/other/pkg/gen.py"
                        )
                        .setDigest(getDigest("external_gen"))
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/pkg/__init__.py"
                        )
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/foo.runfiles/some_repo/pkg/lib.py"
                        )
                        .setDigest(getDigest("external_source"))
                        .setIsTool(inputsMode.isTool)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesMixedRoots() {
        val sourceArtifact: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source.txt")
        Companion.writeFile(sourceArtifact, "source")
        val genArtifact: Artifact = ActionsTestUtil.createArtifact(outputDir, "other/pkg/gen.txt")
        Companion.writeFile(genArtifact, "gen")
        val someRepoPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("pkg"))
        val externalSourceArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalSourceRoot,
                someRepoPkg
                    .getExecPath(siblingRepositoryLayout)
                    .getChild("source.txt")
                    .getPathString()
            )
        Companion.writeFile(externalSourceArtifact, "external_source")
        val someRepoOtherPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"))
        val externalGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalOutputDir,
                someRepoOtherPkg
                    .getPackagePath(siblingRepositoryLayout)
                    .getChild("gen.txt")
                    .getPathString()
            )
        Companion.writeFile(externalGenArtifact, "external_gen")

        val symlinkSourceTarget: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/target.txt")
        Companion.writeFile(symlinkSourceTarget, "symlink_source")
        val symlinkGenTarget: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/target.txt")
        Companion.writeFile(symlinkGenTarget, "symlink_gen")

        val rootSymlinkSourceTarget: Artifact =
            ActionsTestUtil.createArtifact(rootDir, "pkg/root_target.txt")
        Companion.writeFile(rootSymlinkSourceTarget, "root_symlink_source")
        val rootSymlinkGenTarget: Artifact =
            ActionsTestUtil.createArtifact(outputDir, "pkg/root_target.txt")
        Companion.writeFile(rootSymlinkGenTarget, "root_symlink_gen")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")
        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                    "some/symlink", symlinkSourceTarget,
                    "other/symlink", symlinkGenTarget
                ),
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                    "root/symlink", rootSymlinkSourceTarget,
                    "root/other/symlink", rootSymlinkGenTarget
                ),
                sourceArtifact,
                genArtifact,
                externalSourceArtifact,
                externalGenArtifact
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree,
                runfilesArtifact,
                sourceArtifact,
                genArtifact,
                externalSourceArtifact,
                externalGenArtifact,
                symlinkSourceTarget,
                symlinkGenTarget,
                rootSymlinkSourceTarget,
                rootSymlinkGenTarget
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        val builder: SpawnExec.Builder = defaultSpawnExecBuilder()
        builder
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/other/pkg/gen.txt")
                    )
                    .setDigest(getDigest("gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/other/symlink")
                    )
                    .setDigest(getDigest("symlink_gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/pkg/source.txt")
                    )
                    .setDigest(getDigest("source"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/some/symlink")
                    )
                    .setDigest(getDigest("symlink_source"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tools/foo.runfiles/root/other/symlink"
                    )
                    .setDigest(getDigest("root_symlink_gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tools/foo.runfiles/root/symlink")
                    .setDigest(getDigest("root_symlink_source"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/other/pkg/gen.txt"
                    )
                    .setDigest(getDigest("external_gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/source.txt"
                    )
                    .setDigest(getDigest("external_source"))
            )
        closeAndAssertLog(context, builder.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesExternalOnly(
        @TestParameter symlinkUnderMain: Boolean, @TestParameter rootSymlinkUnderMain: Boolean
    ) {
        val someRepoPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("pkg"))
        val externalSourceArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalSourceRoot,
                someRepoPkg
                    .getExecPath(siblingRepositoryLayout)
                    .getChild("source.txt")
                    .getPathString()
            )
        Companion.writeFile(externalSourceArtifact, "external_source")
        val someRepoOtherPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"))
        val externalGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalOutputDir,
                someRepoOtherPkg
                    .getPackagePath(siblingRepositoryLayout)
                    .getChild("gen.txt")
                    .getPathString()
            )
        Companion.writeFile(externalGenArtifact, "external_gen")

        val symlinkTarget: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/root_target.txt")
        Companion.writeFile(symlinkTarget, "symlink_target")
        val rootSymlinkTarget: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/root_target.txt")
        Companion.writeFile(rootSymlinkTarget, "root_symlink_target")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                    (if (symlinkUnderMain) "" else "../some_repo/") + "symlink",
                    symlinkTarget
                ),
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                    (if (rootSymlinkUnderMain) TestConstants.WORKSPACE_NAME + "/" else "some_repo/") + "root_symlink",
                    rootSymlinkTarget
                ),
                externalSourceArtifact,
                externalGenArtifact
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree,
                runfilesArtifact,
                externalSourceArtifact,
                externalGenArtifact,
                symlinkTarget,
                rootSymlinkTarget
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        val files: java.util.ArrayList<File?> = java.util.ArrayList<File?>()
        files.add(
            File.newBuilder()
                .setPath(
                    TestConstants.PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/%s/root_symlink"
                        .formatted(if (rootSymlinkUnderMain) TestConstants.WORKSPACE_NAME else "some_repo")
                )
                .setDigest(getDigest("root_symlink_target"))
                .build()
        )
        files.add(
            File.newBuilder()
                .setPath(
                    TestConstants.PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/%s/symlink"
                        .formatted(if (symlinkUnderMain) TestConstants.WORKSPACE_NAME else "some_repo")
                )
                .setDigest(getDigest("symlink_target"))
                .build()
        )
        files.add(
            File.newBuilder()
                .setPath(
                    TestConstants.PRODUCT_NAME
                            + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/other/pkg/gen.txt"
                )
                .setDigest(getDigest("external_gen"))
                .build()
        )
        files.add(
            File.newBuilder()
                .setPath(
                    TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/source.txt"
                )
                .setDigest(getDigest("external_source"))
                .build()
        )
        if (!symlinkUnderMain && !rootSymlinkUnderMain) {
            files.add(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/.runfile")
                    )
                    .build()
            )
        }
        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addAllInputs(
                    files.stream().sorted(java.util.Comparator.comparing<File?, Any?>(File::getPath)).toList()
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesFilesCollide() {
        val sourceArtifact: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(sourceArtifact, "source")
        val genArtifact: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt")
        Companion.writeFile(genArtifact, "gen")
        val someRepoPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("pkg"))
        val externalSourceArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalSourceRoot,
                someRepoPkg.getExecPath(siblingRepositoryLayout).getChild("file.txt").getPathString()
            )
        Companion.writeFile(externalSourceArtifact, "external_source")
        val externalGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalOutputDir,
                someRepoPkg
                    .getPackagePath(siblingRepositoryLayout)
                    .getChild("file.txt")
                    .getPathString()
            )
        Companion.writeFile(externalGenArtifact, "external_gen")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(),
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(),
                sourceArtifact,
                genArtifact,
                externalSourceArtifact,
                externalGenArtifact
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree,
                runfilesArtifact,
                sourceArtifact,
                genArtifact,
                externalSourceArtifact,
                externalGenArtifact
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        val builder: SpawnExec.Builder = defaultSpawnExecBuilder()
        builder
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/pkg/file.txt")
                    )
                    .setDigest(getDigest("gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/file.txt"
                    )
                    .setDigest(getDigest("external_gen"))
            )
        closeAndAssertLog(context, builder.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesFilesAndSymlinksCollide() {
        val sourceArtifact: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source.txt")
        Companion.writeFile(sourceArtifact, "source")
        val genArtifact: Artifact = ActionsTestUtil.createArtifact(outputDir, "other/pkg/gen.txt")
        Companion.writeFile(genArtifact, "gen")
        val someRepoPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("pkg"))
        val externalSourceArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalSourceRoot,
                someRepoPkg
                    .getExecPath(siblingRepositoryLayout)
                    .getChild("source.txt")
                    .getPathString()
            )
        Companion.writeFile(externalSourceArtifact, "external_source")
        val someRepoOtherPkg: PackageIdentifier =
            PackageIdentifier.create(externalRepo, PathFragment.create("other/pkg"))
        val externalGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(
                externalOutputDir,
                someRepoOtherPkg
                    .getPackagePath(siblingRepositoryLayout)
                    .getChild("gen.txt")
                    .getPathString()
            )
        Companion.writeFile(externalGenArtifact, "external_gen")

        val symlinkSourceArtifact: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/not_source.txt")
        Companion.writeFile(symlinkSourceArtifact, "symlink_source")
        val symlinkGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(outputDir, "other/pkg/not_gen.txt")
        Companion.writeFile(symlinkGenArtifact, "symlink_gen")
        val symlinkExternalSourceArtifact: Artifact =
            ActionsTestUtil.createArtifact(externalSourceRoot, "external/some_repo/pkg/not_source.txt")
        Companion.writeFile(symlinkExternalSourceArtifact, "symlink_external_source")
        val symlinkExternalGenArtifact: Artifact =
            ActionsTestUtil.createArtifact(outputDir, "external/some_repo/other/pkg/not_gen.txt")
        Companion.writeFile(symlinkExternalGenArtifact, "symlink_external_gen")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>( // Symlinks are always relative to the workspace runfiles directory.
                    "pkg/source.txt", symlinkSourceArtifact,
                    "other/pkg/gen.txt", symlinkGenArtifact,
                    "../some_repo/pkg/source.txt", symlinkExternalSourceArtifact,
                    "../some_repo/other/pkg/gen.txt", symlinkExternalGenArtifact
                ),
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(),
                sourceArtifact,
                genArtifact,
                externalSourceArtifact,
                externalGenArtifact
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree,
                runfilesArtifact,
                sourceArtifact,
                genArtifact,
                externalSourceArtifact,
                externalGenArtifact,
                symlinkSourceArtifact,
                symlinkGenArtifact,
                symlinkExternalSourceArtifact,
                symlinkExternalGenArtifact
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        val builder: SpawnExec.Builder = defaultSpawnExecBuilder()
        builder
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/other/pkg/gen.txt")
                    )
                    .setDigest(getDigest("gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        (TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                + TestConstants.WORKSPACE_NAME
                                + "/pkg/source.txt")
                    )
                    .setDigest(getDigest("source"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/other/pkg/gen.txt"
                    )
                    .setDigest(getDigest("external_gen"))
            )
            .addInputs(
                File.newBuilder()
                    .setPath(
                        TestConstants.PRODUCT_NAME
                                + "-out/k8-fastbuild/bin/tools/foo.runfiles/some_repo/pkg/source.txt"
                    )
                    .setDigest(getDigest("external_source"))
            )
        closeAndAssertLog(context, builder.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesFileAndRootSymlinkCollide() {
        val sourceArtifact: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source.txt")
        Companion.writeFile(sourceArtifact, "source")

        val symlinkSourceArtifact: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/not_source.txt")
        Companion.writeFile(symlinkSourceArtifact, "symlink_source")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(),
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                    TestConstants.WORKSPACE_NAME + "/pkg/source.txt",
                    symlinkSourceArtifact
                ),
                sourceArtifact
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree, runfilesArtifact, sourceArtifact, symlinkSourceArtifact
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/source.txt")
                        )
                        .setDigest(getDigest("symlink_source"))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesCrossTypeCollision(@TestParameter symlinkFirst: Boolean) {
        val file: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(file, "file")
        val symlink: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "pkg/file.txt")
        symlink.getPath().getParentDirectory().createDirectoryAndParents()
        symlink.getPath().createSymbolicLink(PathFragment.create("/some/path/other_file.txt"))

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val artifacts: com.google.common.collect.ImmutableList<Any?> =
            if (symlinkFirst) com.google.common.collect.ImmutableList.of<Any?>(
                symlink,
                file
            ) else com.google.common.collect.ImmutableList.of<Any?>(file, symlink)
        val runfilesTree: RunfilesTree? =
            createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                NestedSetBuilder.wrap(Order.STABLE_ORDER, artifacts)
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(runfilesTree, runfilesArtifact, file, symlink),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    if (symlinkFirst)
                        File.newBuilder()
                            .setPath(
                                (TestConstants.PRODUCT_NAME
                                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                        + TestConstants.WORKSPACE_NAME
                                        + "/pkg/file.txt")
                            )
                            .setDigest(getDigest("file"))
                    else
                        File.newBuilder()
                            .setPath(
                                (TestConstants.PRODUCT_NAME
                                        + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                        + TestConstants.WORKSPACE_NAME
                                        + "/pkg/file.txt")
                            )
                            .setSymlinkTargetPath("/some/path/other_file.txt")
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesPostOrderCollision(@TestParameter nestBoth: Boolean) {
        val sourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(sourceFile, "source")
        val genFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt")
        Companion.writeFile(genFile, "gen")
        val otherSourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/other_file.txt")
        Companion.writeFile(otherSourceFile, "other_source")
        val otherGenFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/other_file.txt")
        Companion.writeFile(otherGenFile, "other_gen")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val artifactsBuilder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.< Artifact > compileOrder < Artifact ? > ()
                .addTransitive(
                    NestedSetBuilder.wrap(
                        Order.COMPILE_ORDER, com.google.common.collect.ImmutableList.of<E?>(sourceFile, otherGenFile)
                    )
                )
        val remainingArtifacts: com.google.common.collect.ImmutableList<Any?> =
            com.google.common.collect.ImmutableList.of<Any?>(genFile, otherSourceFile)
        if (nestBoth) {
            artifactsBuilder.addTransitive(
                NestedSetBuilder.wrap(Order.COMPILE_ORDER, remainingArtifacts)
            )
        } else {
            artifactsBuilder.addAll(remainingArtifacts)
        }
        val artifacts: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            artifactsBuilder.build()
        assertThat(artifacts.toList())
            .containsExactly(sourceFile, otherGenFile, genFile, otherSourceFile)
            .inOrder()
        if (nestBoth) {
            assertThat(artifacts.getNonLeaves()).hasSize(2)
        }

        val runfilesTree: RunfilesTree? =
            createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                artifacts
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(
                runfilesTree, runfilesArtifact, sourceFile, genFile, otherSourceFile, otherGenFile
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/file.txt")
                        )
                        .setDigest(getDigest("gen"))
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/other_file.txt")
                        )
                        .setDigest(getDigest("other_source"))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactPostOrderCollisionWithDuplicate() {
        val sourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(sourceFile, "source")
        val genFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt")
        Companion.writeFile(genFile, "gen")

        val runfilesTreeArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val artifacts: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.< Artifact > compileOrder < Artifact ? > ()
                .add(sourceFile)
                .addTransitive(
                    NestedSetBuilder.wrap(
                        Order.COMPILE_ORDER,
                        com.google.common.collect.ImmutableList.of<E?>(sourceFile, genFile)
                    )
                )
                .build()
        assertThat(artifacts.toList()).containsExactly(sourceFile, genFile).inOrder()
        assertThat(artifacts.getLeaves()).hasSize(1)
        assertThat(artifacts.getNonLeaves()).hasSize(1)

        val runfilesTree: RunfilesTree? =
            createRunfilesTree(
                runfilesRoot,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                artifacts
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesTreeArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(runfilesTree, runfilesTreeArtifact, sourceFile, genFile),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/file.txt")
                        )
                        .setDigest(getDigest("gen"))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesSymlinkPostOrderCollisionWithSemanticDuplicate(
        @TestParameter rootSymlink: Boolean
    ) {
        val sourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(sourceFile, "source")
        val genFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt")
        Companion.writeFile(genFile, "gen")

        val runfilesTreeArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfiles: Runfiles.Builder = Builder(TestConstants.WORKSPACE_NAME)
        if (rootSymlink) {
            val transitiveRunfiles: Runfiles =
                Builder(TestConstants.WORKSPACE_NAME)
                    .addRootSymlink(PathFragment.create(TestConstants.WORKSPACE_NAME + "/pkg/file.txt"), sourceFile)
                    .addRootSymlink(PathFragment.create(TestConstants.WORKSPACE_NAME + "/pkg/file.txt"), genFile)
                    .build()
            runfiles.addRootSymlink(PathFragment.create(TestConstants.WORKSPACE_NAME + "/pkg/file.txt"), sourceFile)
            runfiles.addRootSymlinks(transitiveRunfiles.getRootSymlinks())
        } else {
            val transitiveRunfiles: Runfiles =
                Builder(TestConstants.WORKSPACE_NAME)
                    .addSymlink(PathFragment.create("pkg/file.txt"), sourceFile)
                    .addSymlink(PathFragment.create("pkg/file.txt"), genFile)
                    .build()
            runfiles.addSymlink(PathFragment.create("pkg/file.txt"), sourceFile)
            runfiles.addSymlinks(transitiveRunfiles.getSymlinks())
        }
        val runfilesTree: RunfilesTree =
            RunfilesTreeImpl(runfilesRoot, runfiles.build())

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesTreeArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(runfilesTree, runfilesTreeArtifact, sourceFile, genFile),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/file.txt")
                        )
                        .setDigest(getDigest("source"))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesSymlinkPostOrderCollisionWithEqualDuplicate(
        @TestParameter rootSymlink: Boolean
    ) {
        val sourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(sourceFile, "source")
        val genFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "pkg/file.txt")
        Companion.writeFile(genFile, "gen")

        val runfilesTreeArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfiles: Runfiles.Builder = Builder(TestConstants.WORKSPACE_NAME)
        // Arrange for (reference) equal SymlinkEntry instances to appear twice in the runfiles, both
        // first and last in compile order.
        if (rootSymlink) {
            val transitiveRunfiles: Runfiles =
                Builder(TestConstants.WORKSPACE_NAME)
                    .addRootSymlink(PathFragment.create(TestConstants.WORKSPACE_NAME + "/pkg/file.txt"), sourceFile)
                    .addRootSymlink(PathFragment.create(TestConstants.WORKSPACE_NAME + "/pkg/file.txt"), genFile)
                    .build()
            runfiles.addRootSymlinks(
                NestedSetBuilder.wrap(
                    Order.STABLE_ORDER, transitiveRunfiles.getRootSymlinks().toList().subList(0, 1)
                )
            )
            runfiles.addRootSymlinks(transitiveRunfiles.getRootSymlinks())
        } else {
            val transitiveRunfiles: Runfiles =
                Builder(TestConstants.WORKSPACE_NAME)
                    .addSymlink(PathFragment.create("pkg/file.txt"), sourceFile)
                    .addSymlink(PathFragment.create("pkg/file.txt"), genFile)
                    .build()
            runfiles.addSymlinks(
                NestedSetBuilder.wrap(
                    Order.STABLE_ORDER, transitiveRunfiles.getSymlinks().toList().subList(0, 1)
                )
            )
            runfiles.addSymlinks(transitiveRunfiles.getSymlinks())
        }
        val runfilesTree: RunfilesTree =
            RunfilesTreeImpl(runfilesRoot, runfiles.build())

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesTreeArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(runfilesTree, runfilesTreeArtifact, sourceFile, genFile),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        // The compact log can't distinguish between semantically equal and reference equal SymlinkEntry
        // instances and thus the reconstructor can't deduplicate them as NestedSet would. This is a
        // pathological case that can be recreated in Starlark, but it would cause a conflict error
        // unless using --nobuild_runfile_manifests.
        val expectedContent = if (this is CompactSpawnLogContextTest) "source" else "gen"
        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/file.txt")
                        )
                        .setDigest(getDigest(expectedContent))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesSymlinkTargets(
        @TestParameter rootSymlinks: Boolean, @TestParameter inputsMode: InputsMode
    ) {
        val sourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        Companion.writeFile(sourceFile, "source")
        val sourceDir: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/source_dir")
        sourceDir.getPath().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(
            sourceDir.getPath().getRelative("some_file"), "source_dir_file"
        )
        val genDir: Artifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "pkg/gen_dir")
        genDir.getPath().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(
            genDir.getPath().getRelative("other_file"), "gen_dir_file"
        )
        val symlink: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "pkg/symlink")
        symlink.getPath().getParentDirectory().createDirectoryAndParents()
        symlink.getPath().createSymbolicLink(PathFragment.create("/some/path"))

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                if (rootSymlinks)
                    com.google.common.collect.ImmutableMap.of<String?, Artifact?>()
                else
                    com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                        "file", sourceFile,
                        "source_dir", sourceDir,
                        "gen_dir", genDir,
                        "symlink", symlink
                    ),
                if (rootSymlinks)
                    com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                        TestConstants.WORKSPACE_NAME + "/file", sourceFile,
                        TestConstants.WORKSPACE_NAME + "/source_dir", sourceDir,
                        TestConstants.WORKSPACE_NAME + "/gen_dir", genDir,
                        TestConstants.WORKSPACE_NAME + "/symlink", symlink
                    )
                else
                    com.google.common.collect.ImmutableMap.of<String?, Artifact?>()
            )

        val spawnBuilder: SpawnBuilder = defaultSpawnBuilder().withInput(runfilesArtifact)
        if (inputsMode.isTool) {
            spawnBuilder.withTool(runfilesArtifact)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawnBuilder.build(),
            Companion.createInputMetadataProvider(
                runfilesTree, runfilesArtifact, sourceFile, sourceDir, genDir, symlink
            ),
            Companion.createInputMap(runfilesTree),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/file")
                        )
                        .setDigest(getDigest("source"))
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/gen_dir/other_file")
                        )
                        .setDigest(getDigest("gen_dir_file"))
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/source_dir/some_file")
                        )
                        .setDigest(getDigest("source_dir_file"))
                        .setIsTool(inputsMode.isTool)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/symlink")
                        )
                        .setSymlinkTargetPath("/some/path")
                        .setIsTool(inputsMode.isTool)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfileSymlinkFileWithDirectoryContents(
        @TestParameter rootSymlink: Boolean, @TestParameter outputsMode: OutputsMode
    ) {
        val sourceFile: Artifact = ActionsTestUtil.createArtifact(rootDir, "pkg/file.txt")
        sourceFile.getPath().createDirectoryAndParents()
        writeFile(sourceFile.getPath().getChild("file"), "abc")

        val runfilesArtifact: Artifact =
            ActionsTestUtil.createRunfilesArtifact(outputDir, "tools/foo.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("tools/foo.runfiles")
        val runfilesTree: RunfilesTree? =
            Companion.createRunfilesTree(
                runfilesRoot,
                if (rootSymlink) com.google.common.collect.ImmutableMap.of<String?, Artifact?>() else com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                    "pkg/symlink",
                    sourceFile
                ),
                if (rootSymlink)
                    com.google.common.collect.ImmutableMap.of<String?, Artifact?>(
                        TestConstants.WORKSPACE_NAME + "/pkg/symlink",
                        sourceFile
                    )
                else
                    com.google.common.collect.ImmutableMap.of<String?, Artifact?>()
            )

        val spawn: Spawn = defaultSpawnBuilder().withInput(runfilesArtifact).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(runfilesTree, runfilesArtifact, sourceFile),
            Companion.createInputMap(runfilesTree),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/tools/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/pkg/symlink/file")
                        )
                        .setDigest(getDigest("abc"))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesetInput(@TestParameter dirContents: DirContents) {
        val filesetInput: Artifact =
            SpecialArtifact.create(
                outputDir,
                outputDir.getExecPath().getRelative("dir"),
                ActionsTestUtil.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.FILESET
            )

        filesetInput.getPath().createDirectoryAndParents()
        if (!dirContents.isEmpty) {
            writeFile(fs.getPath("/file.txt"), "abc")
            filesetInput
                .getPath()
                .getChild("file.txt")
                .createSymbolicLink(PathFragment.create("/file.txt"))
        }

        val spawn: Spawn = defaultSpawnBuilder().withInput(filesetInput).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            Companion.createInputMetadataProvider(filesetInput),
            createInputMap(filesetInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addAllInputs(
                    if (dirContents.isEmpty)
                        com.google.common.collect.ImmutableList.of<E?>()
                    else
                        com.google.common.collect.ImmutableList.of<E?>(
                            File.newBuilder()
                                .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/dir/file.txt")
                                .setDigest(getDigest("abc"))
                                .build()
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParamFileInput() {
        val paramFileInput: ParamFileActionInput =
            ParamFileActionInput(
                PathFragment.create("foo.params"),
                com.google.common.collect.ImmutableList.of<E?>("a", "b", "c"),
                ParameterFileType.UNQUOTED
            )

        // Do not materialize the file on disk, which would be the case when running remotely.
        val spawn: SpawnBuilder = defaultSpawnBuilder().withInputs(paramFileInput)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),  // ParamFileActionInputs appear in the input map but not in the metadata provider.
            createInputMetadataProvider(),
            createInputMap(paramFileInput),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addInputs(File.newBuilder().setPath("foo.params").setDigest(getDigest("a\nb\nc\n")))
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileOutput(
        @TestParameter outputsMode: OutputsMode, @TestParameter indirection: OutputIndirection
    ) {
        val fileOutput: Artifact = ActionsTestUtil.createArtifact(outputDir, "file")

        val actualPath: Path =
            if (indirection.viaSymlink())
                outputDir.getRoot().asPath().getChild("actual")
            else
                fileOutput.getPath()

        if (indirection.viaSymlink()) {
            fileOutput.getPath().getParentDirectory().createDirectoryAndParents()
            fileOutput.getPath().createSymbolicLink(actualPath)
        }

        Companion.writeFile(actualPath, "abc")

        val spawn: Spawn = defaultSpawnBuilder().withOutputs(fileOutput).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/file")
                .addActualOutputs(
                    File.newBuilder()
                        .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/file")
                        .setDigest(getDigest("abc"))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileOutputWithInvalidType(@TestParameter outputsMode: OutputsMode) {
        val fileOutput: Artifact = ActionsTestUtil.createArtifact(outputDir, "file")

        fileOutput.getPath().createDirectoryAndParents()
        writeFile(fileOutput.getPath().getChild("file"), "abc")

        val spawn: SpawnBuilder = defaultSpawnBuilder().withOutputs(fileOutput)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/file")
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeOutput(
        @TestParameter outputsMode: OutputsMode,
        @TestParameter dirContents: DirContents,
        @TestParameter indirection: OutputIndirection
    ) {
        val treeOutput: SpecialArtifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree")

        val actualPath: Path =
            if (indirection.viaSymlink())
                outputDir.getRoot().asPath().getChild("actual")
            else
                treeOutput.getPath()

        if (indirection.viaSymlink()) {
            treeOutput.getPath().getParentDirectory().createDirectoryAndParents()
            treeOutput.getPath().createSymbolicLink(actualPath)
        }

        actualPath.createDirectoryAndParents()
        if (!dirContents.isEmpty) {
            val firstChildPath: Path = actualPath.getRelative("dir1/file1")
            val secondChildPath: Path = actualPath.getRelative("dir2/file2")
            firstChildPath.getParentDirectory().createDirectoryAndParents()
            secondChildPath.getParentDirectory().createDirectoryAndParents()
            Companion.writeFile(firstChildPath, "abc")
            Companion.writeFile(secondChildPath, "def")
            val emptySubdirPath: Path = actualPath.getRelative("dir3")
            emptySubdirPath.createDirectoryAndParents()
        }

        val spawn: Spawn = defaultSpawnBuilder().withOutputs(treeOutput).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tree")
                .addAllActualOutputs(
                    if (dirContents.isEmpty)
                        com.google.common.collect.ImmutableList.of<E?>()
                    else
                        com.google.common.collect.ImmutableList.of<E?>(
                            File.newBuilder()
                                .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tree/dir1/file1")
                                .setDigest(getDigest("abc"))
                                .build(),
                            File.newBuilder()
                                .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tree/dir2/file2")
                                .setDigest(getDigest("def"))
                                .build()
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeOutputWithInvalidType(@TestParameter outputsMode: OutputsMode) {
        val treeOutput: Artifact = ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree")

        Companion.writeFile(treeOutput, "abc")

        val spawn: SpawnBuilder = defaultSpawnBuilder().withOutputs(treeOutput)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/tree")
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnresolvedSymlinkOutput(@TestParameter outputsMode: OutputsMode) {
        val symlinkOutput: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink")

        symlinkOutput.getPath().getParentDirectory().createDirectoryAndParents()
        symlinkOutput.getPath().createSymbolicLink(PathFragment.create("/some/path"))

        val spawn: SpawnBuilder = defaultSpawnBuilder().withOutputs(symlinkOutput)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
                .addActualOutputs(
                    File.newBuilder()
                        .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
                        .setSymlinkTargetPath("/some/path")
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnresolvedSymlinkOutputWithInvalidType(@TestParameter outputsMode: OutputsMode) {
        val symlinkOutput: Artifact = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink")

        Companion.writeFile(symlinkOutput, "abc")

        val spawn: SpawnBuilder = defaultSpawnBuilder().withOutputs(symlinkOutput)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/symlink")
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingOutput(@TestParameter outputsMode: OutputsMode) {
        val missingOutput: Artifact? = ActionsTestUtil.createArtifact(outputDir, "missing")

        val spawn: SpawnBuilder = defaultSpawnBuilder().withOutputs(missingOutput)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            createInputMetadataProvider(),
            createInputMap(),
            outputsMode.getActionFileSystem(fs),
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/missing")
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironment() {
        val spawn: Spawn =
            defaultSpawnBuilder().withEnvironment("SPAM", "eggs").withEnvironment("FOO", "bar").build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .addEnvironmentVariables(
                    EnvironmentVariable.newBuilder().setName("FOO").setValue("bar")
                )
                .addEnvironmentVariables(
                    EnvironmentVariable.newBuilder().setName("SPAM").setValue("eggs")
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultPlatformProperties() {
        val context: SpawnLogContext =
            createSpawnLogContext(com.google.common.collect.ImmutableMap.of<String?, String?>("a", "1", "b", "2"))

        context.logSpawn(
            defaultSpawn(),
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .setPlatform(
                    Platform.newBuilder()
                        .addProperties(Platform.Property.newBuilder().setName("a").setValue("1"))
                        .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                        .build()
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpawnPlatformProperties() {
        val spawn: Spawn =
            defaultSpawnBuilder()
                .withCombinedExecProperties(
                    com.google.common.collect.ImmutableMap.of<String?, String?>(
                        "a",
                        "3",
                        "c",
                        "4"
                    )
                )
                .build()

        val context: SpawnLogContext =
            createSpawnLogContext(com.google.common.collect.ImmutableMap.of<String?, String?>("a", "1", "b", "2"))

        context.logSpawn(
            spawn,
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        // The spawn properties should override the default properties.
        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .setPlatform(
                    Platform.newBuilder()
                        .addProperties(Platform.Property.newBuilder().setName("a").setValue("3"))
                        .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                        .addProperties(Platform.Property.newBuilder().setName("c").setValue("4"))
                        .build()
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo(
        @TestParameter("no-remote", "no-cache", "no-remote-cache") requirement: String
    ) {
        val spawn: Spawn = defaultSpawnBuilder().withExecutionInfo(requirement, "").build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn,
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .setRemotable(requirement != "no-remote")
                .setCacheable(requirement != "no-cache")
                .setRemoteCacheable(
                    (requirement != "no-cache") && (requirement != "no-remote") && (requirement != "no-remote-cache")
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheHit() {
        val context: SpawnLogContext = createSpawnLogContext()

        val result: SpawnResult? = defaultSpawnResultBuilder().setCacheHit(true).build()

        context.logSpawn(
            defaultSpawn(),
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            result
        )

        closeAndAssertLog(context, defaultSpawnExecBuilder().setCacheHit(true).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDigest() {
        val context: SpawnLogContext = createSpawnLogContext()

        val digest: Digest = getDigest("something")

        val result: SpawnResult? = defaultSpawnResultBuilder().setDigest(digest).build()

        context.logSpawn(
            defaultSpawn(),
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            result
        )

        closeAndAssertLog(context, defaultSpawnExecBuilder().setDigest(digest).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTimeout() {
        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            defaultSpawn(),
            createInputMetadataProvider(),
            createInputMap(),
            fs,  /* timeout= */
            java.time.Duration.ofSeconds(42),
            defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder().setTimeoutMillis(java.time.Duration.ofSeconds(42).toMillis()).build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpawnMetrics() {
        val metrics: SpawnMetrics? = SpawnMetrics.Builder.forLocalExec().setTotalTimeInMs(1).build()

        val context: SpawnLogContext = createSpawnLogContext()

        val now: Instant = Instant.now()
        context.logSpawn(
            defaultSpawn(),
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            defaultSpawnResultBuilder().setSpawnMetrics(metrics).setStartTime(now).build()
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .setMetrics(
                    Protos.SpawnMetrics.newBuilder()
                        .setTotalTime(millisToProto(1))
                        .setStartTime(Timestamps.fromDate(java.util.Date.from(now)))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStatus() {
        val context: SpawnLogContext = createSpawnLogContext()

        // SpawnResult requires a non-zero exit code and non-null failure details when the status isn't
        // successful.
        val result: SpawnResult? =
            defaultSpawnResultBuilder()
                .setStatus(Status.NON_ZERO_EXIT)
                .setExitCode(37)
                .setFailureDetail(
                    FailureDetail.newBuilder()
                        .setMessage("oops")
                        .setCrash(Crash.getDefaultInstance())
                        .build()
                )
                .build()

        context.logSpawn(
            defaultSpawn(),
            createInputMetadataProvider(),
            createInputMap(),
            fs,
            defaultTimeout(),
            result
        )

        closeAndAssertLog(
            context,
            defaultSpawnExecBuilder()
                .setExitCode(37)
                .setStatus(Status.NON_ZERO_EXIT.toString())
                .build()
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    protected fun createSpawnLogContext(): SpawnLogContext {
        return createSpawnLogContext(com.google.common.collect.ImmutableSortedMap.of<String?, String?>())
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    protected abstract fun createSpawnLogContext(
        platformProperties: com.google.common.collect.ImmutableMap<String?, String?>?
    ): SpawnLogContext

    protected fun getDigest(content: String): Digest {
        return Digest.newBuilder()
            .setHash(
                digestHashFunction.getHashFunction().hashString(content, java.nio.charset.StandardCharsets.UTF_8)
                    .toString()
            )
            .setSizeBytes(com.google.common.base.Utf8.encodedLength(content))
            .setHashFunctionName(digestHashFunction.toString())
            .build()
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    protected abstract fun closeAndAssertLog(context: SpawnLogContext?, vararg expected: SpawnExec?)

    companion object {
        protected fun defaultTimeout(): java.time.Duration {
            return java.time.Duration.ZERO
        }

        protected fun defaultSpawnBuilder(): SpawnBuilder {
            return SpawnBuilder("cmd", "--opt")
        }

        protected fun defaultSpawn(): Spawn {
            return defaultSpawnBuilder().build()
        }

        protected fun defaultSpawnResultBuilder(): SpawnResult.Builder {
            return Builder().setRunnerName("runner").setStatus(Status.SUCCESS)
        }

        protected fun defaultSpawnResult(): SpawnResult {
            return defaultSpawnResultBuilder().build()
        }

        protected fun defaultSpawnExecBuilder(): SpawnExec.Builder {
            return SpawnExec.newBuilder()
                .addCommandArgs("cmd")
                .addCommandArgs("--opt")
                .setRunner("runner")
                .setRemotable(true)
                .setCacheable(true)
                .setRemoteCacheable(true)
                .setMnemonic("Mnemonic")
                .setTargetLabel("//dummy:label")
                .setMetrics(Protos.SpawnMetrics.getDefaultInstance())
        }

        protected fun createRunfilesTree(root: PathFragment?, vararg artifacts: Artifact?): RunfilesTree? {
            return Companion.createRunfilesTree(
                root,
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(),
                com.google.common.collect.ImmutableMap.of<String?, Artifact?>(),
                *artifacts
            )
        }

        protected fun createRunfilesTree(
            root: PathFragment?,
            symlinks: MutableMap<String?, Artifact?>,
            rootSymlinks: MutableMap<String?, Artifact?>,
            artifacts: NestedSet<Artifact?>?
        ): RunfilesTree {
            val runfiles: Runfiles.Builder = Builder(TestConstants.WORKSPACE_NAME)
            runfiles.addTransitiveArtifacts(artifacts)
            for (entry in symlinks.entries) {
                runfiles.addSymlink(PathFragment.create(entry.key), entry.value)
            }
            for (entry in rootSymlinks.entries) {
                runfiles.addRootSymlink(PathFragment.create(entry.key), entry.value)
            }
            runfiles.setEmptyFilesSupplier(BazelPyBuiltins.GET_INIT_PY_FILES)
            return RunfilesTreeImpl(root, runfiles.build())
        }

        protected fun createRunfilesTree(
            root: PathFragment?,
            symlinks: MutableMap<String?, Artifact?>?,
            rootSymlinks: MutableMap<String?, Artifact?>?,
            vararg artifacts: Artifact?
        ): RunfilesTree? {
            return createRunfilesTree(
                root,
                symlinks,
                rootSymlinks,
                NestedSetBuilder.wrap(Order.COMPILE_ORDER, java.util.Arrays.< T > asList < T ? > (artifacts))
            )
        }

        @Throws(java.lang.Exception::class)
        protected fun createInputMetadataProvider(vararg artifacts: Artifact): InputMetadataProvider {
            return Companion.createInputMetadataProvider(null, *artifacts)
        }

        @Throws(java.lang.Exception::class)
        protected fun createInputMetadataProvider(
            runfilesTree: RunfilesTree?, vararg artifacts: Artifact
        ): InputMetadataProvider {
            val builder: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
                com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
            for (artifact in artifacts) {
                if (artifact.isTreeArtifact()) {
                    // Emulate ActionInputMap: add both tree and children.
                    val treeMetadata: TreeArtifactValue = createTreeArtifactValue(artifact)
                    builder.put(artifact, treeMetadata.getMetadata())
                    for (entry in treeMetadata.getChildValues().entrySet()) {
                        builder.put(entry.key, entry.value)
                    }
                } else if (artifact.isSymlink()) {
                    builder.put(artifact, FileArtifactValue.createForUnresolvedSymlink(artifact))
                } else if (artifact.isRunfilesTree()) {
                    builder.putRunfilesTree(artifact, runfilesTree)
                } else {
                    builder.put(artifact, FileArtifactValue.createForTesting(artifact))
                }
            }
            return builder
        }

        @Throws(java.lang.Exception::class)
        protected fun createInputMap(vararg actionInputs: ActionInput): SortedMap<PathFragment?, ActionInput?> {
            return Companion.createInputMap(null, *actionInputs)
        }

        @Throws(java.lang.Exception::class)
        protected fun createInputMap(
            runfilesTree: RunfilesTree?, vararg actionInputs: ActionInput
        ): SortedMap<PathFragment?, ActionInput?> {
            val builder: TreeMap<PathFragment?, ActionInput?> = TreeMap<PathFragment?, ActionInput?>()

            val inputMetadataProvider: InputMetadataProvider =
                Mockito.mock<InputMetadataProvider>(InputMetadataProvider::class.java)
            Mockito.`when`<T?>(inputMetadataProvider.getTreeMetadata(ArgumentMatchers.any<T?>()))
                .thenAnswer(
                    Answer { invocation: InvocationOnMock? ->
                        val treeArtifact: SpecialArtifact = invocation.getArgument<SpecialArtifact>(0)
                        createTreeArtifactValue(treeArtifact)
                    })

            if (runfilesTree != null) {
                SpawnInputExpander()
                    .addSingleRunfilesTreeToInputs(
                        runfilesTree,
                        builder,
                        inputMetadataProvider,
                        PathMapper.NOOP,
                        PathFragment.EMPTY_FRAGMENT
                    )
            }

            for (actionInput in actionInputs) {
                if (actionInput is Artifact && actionInput.isTreeArtifact()) {
                    // Emulate SpawnInputExpander: expand to children, preserve if empty.
                    val treeMetadata: TreeArtifactValue = createTreeArtifactValue(actionInput)
                    if (treeMetadata.getChildren().isEmpty()) {
                        builder.put(actionInput.getExecPath(), actionInput)
                    } else {
                        for (child in treeMetadata.getChildren()) {
                            builder.put(child.getExecPath(), child)
                        }
                    }
                } else {
                    builder.put(actionInput.getExecPath(), actionInput)
                }
            }
            return com.google.common.collect.ImmutableSortedMap.copyOf<PathFragment?, ActionInput?>(builder)
        }

        @Throws(java.lang.Exception::class)
        protected fun createTreeArtifactValue(tree: Artifact): TreeArtifactValue {
            com.google.common.base.Preconditions.checkState(tree.isTreeArtifact())
            val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(tree as SpecialArtifact)
            TreeArtifactValue.visitTree(
                tree.getPath(),
                { parentRelativePath, type, traversedSymlink ->
                    if (type.equals(Dirent.Type.DIRECTORY)) {
                        return@visitTree
                    }
                    val child: TreeFileArtifact? =
                        TreeFileArtifact.createTreeOutput(tree as SpecialArtifact, parentRelativePath)
                    builder.putChild(child, FileArtifactValue.createForTesting(child))
                })
            return builder.build()
        }

        @Throws(IOException::class)
        protected fun writeFile(artifact: Artifact, contents: String?) {
            writeFile(artifact.getPath(), contents)
        }

        @Throws(IOException::class)
        protected fun writeFile(path: Path, contents: String?) {
            path.getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContent(path, java.nio.charset.StandardCharsets.UTF_8, contents)
        }
    }
}
