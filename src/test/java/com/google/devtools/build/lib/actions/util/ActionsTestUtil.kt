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
package com.google.devtools.build.lib.actions.util

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.collect.*
import com.google.devtools.build.lib.actions.AbstractAction
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.baseArtifactNames
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.baseNamesOf
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.createTreeArtifactWithGeneratingAction
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.execPaths
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.getFirstArtifactEndingWith
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.prettyArtifactNames
import com.google.devtools.build.lib.actions.util.ActionsTestUtil.Companion.sortedBaseNamesOf
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.syntax.Location
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.Queue
import java.util.function.Function
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** A bunch of utilities that are useful for tests concerning actions, artifacts, etc.  */
class ActionsTestUtil(actionGraph: ActionGraph) {
    private val actionGraph: ActionGraph

    /** An unchecked exception class for action conflicts.  */
    class UncheckedActionConflictException(e: ActionConflictException?) : RuntimeException(e)

    /** A dummy Action class for use in tests.  */
    open class NullAction : AbstractAction {
        constructor() : super(
            NULL_ACTION_OWNER,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableList.of<E?>(DUMMY_ARTIFACT)
        )

        constructor(owner: ActionOwner?, vararg outputs: Artifact?) : super(
            owner,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableList.< E > copyOf < E ? > (outputs)
        )

        constructor(owner: ActionOwner?, inputs: NestedSet<Artifact?>?) : super(
            owner, inputs, ImmutableList.of<E?>(
                DUMMY_ARTIFACT
            )
        )

        constructor(vararg outputs: Artifact?) : super(
            NULL_ACTION_OWNER,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            ImmutableList.< E > copyOf < E ? > (outputs)
        )

        constructor(inputs: MutableList<Artifact?>?, vararg outputs: Artifact?) : super(
            NULL_ACTION_OWNER,
            NestedSetBuilder.wrap(Order.STABLE_ORDER, inputs),
            ImmutableList.< E > copyOf < E ? > (outputs)
        )

        public override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
            return ActionResult.EMPTY
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addString("action")
        }

        public override fun getMnemonic(): String? {
            return "Null"
        }
    }

    /** [NullAction] that can be used in place of a shadowed action that discovers inputs.  */
    class InputDiscoveringNullAction : NullAction() {
        public override fun discoversInputs(): Boolean {
            return true
        }

        protected override fun inputsDiscovered(): Boolean {
            return false
        }
    }

    /**
     * A mocked action containing the inputs and outputs of the action. Used for tests that do not
     * need to execute the action.
     */
    open class MockAction(inputs: Iterable<Artifact?>?, outputs: ImmutableSet<Artifact?>?, isShareable: Boolean) :
        AbstractAction(
            NULL_ACTION_OWNER,
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addAll(inputs).build(),
            outputs
        ) {
        private val isShareable: Boolean

        constructor(inputs: Iterable<Artifact?>?, outputs: ImmutableSet<Artifact?>?) : this(
            inputs,
            outputs,  /* isShareable= */
            true
        )

        init {
            this.isShareable = isShareable
        }

        public override fun getMnemonic(): String {
            return "Mock action"
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addString("Mock Action " + getPrimaryOutput())
        }

        public override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult? {
            throw UnsupportedOperationException()
        }

        public override fun isShareable(): Boolean {
            return isShareable
        }
    }

    /**
     * Returns the closure of the predecessors of any of the given types, joining the basenames of the
     * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
     */
    fun predecessorClosureOf(artifact: Artifact?, vararg types: FileType?): String? {
        return predecessorClosureOf(Collections.singleton<Artifact?>(artifact), *types)
    }

    /**
     * Returns the closure of the predecessors of any of the given types, joining the basenames of the
     * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
     */
    fun predecessorClosureOf(artifacts: NestedSet<Artifact?>, vararg types: FileType?): String? {
        return predecessorClosureOf(artifacts.toList(), types)
    }

    /**
     * Returns the closure of the predecessors of any of the given types, joining the basenames of the
     * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
     */
    fun predecessorClosureOf(artifacts: Iterable<Artifact?>, vararg types: FileType?): String? {
        val visited: MutableSet<Artifact?> = artifactClosureOf(artifacts)
        return baseNamesOf(FileType.filter(visited, types))
    }

    /** Returns the closure of the predecessors of any of the given types.  */
    fun predecessorClosureAsCollection(artifact: Artifact?, vararg types: FileType?): MutableCollection<String?>? {
        return predecessorClosureAsCollection(Collections.singleton<Artifact?>(artifact), *types)
    }

    /** Returns the closure of the predecessors of any of the given types.  */
    fun predecessorClosureAsCollection(
        artifacts: NestedSet<Artifact?>, vararg types: FileType?
    ): MutableCollection<String?>? {
        return predecessorClosureAsCollection(artifacts.toList(), types)
    }

    /** Returns the closure of the predecessors of any of the given types.  */
    fun predecessorClosureAsCollection(
        artifacts: Iterable<Artifact?>, vararg types: FileType?
    ): MutableCollection<String?>? {
        return baseArtifactNames(FileType.filter(artifactClosureOf(artifacts), types))
    }

    /** Returns the closure over the input files of an action.  */
    fun inputClosureOf(action: ActionAnalysisMetadata): MutableSet<Artifact?>? {
        return artifactClosureOf(action.getInputs().toList())
    }

    /** Returns the closure over the input files of an artifact.  */
    fun artifactClosureOf(artifact: Artifact?): MutableSet<Artifact?> {
        return artifactClosureOf(Collections.singleton<Artifact?>(artifact))
    }

    /** Returns the closure over the input files of a set of artifacts.  */
    fun artifactClosureOf(artifacts: NestedSet<Artifact?>): MutableSet<Artifact?>? {
        return artifactClosureOf(artifacts.toList())
    }

    /** Returns the closure over the input files of a set of artifacts.  */
    fun artifactClosureOf(artifacts: Iterable<Artifact?>): MutableSet<Artifact?> {
        val visited: MutableSet<Artifact?> = LinkedHashSet<Artifact?>()
        val toVisit: MutableList<Artifact?> = Lists.newArrayList<Artifact?>(artifacts)
        while (!toVisit.isEmpty()) {
            val current: Artifact? = toVisit.remove(0)
            if (!visited.add(current)) {
                continue
            }
            val generatingAction: ActionAnalysisMetadata? = actionGraph.getGeneratingAction(current)
            if (generatingAction != null) {
                toVisit.addAll(generatingAction.getInputs().toList())
            }
        }
        return visited
    }

    /** Returns the closure over the input files of an artifact, filtered by the given matcher.  */
    fun filteredArtifactClosureOf(
        artifact: Artifact?, matcher: Predicate<Artifact?>?
    ): ImmutableSet<Artifact?> {
        return artifactClosureOf(artifact).stream().filter(matcher).collect(ImmutableSet.toImmutableSet<Artifact?>())
    }

    /**
     * Finds all the actions that are instances of `actionClass` in the transitive closure of
     * prerequisites.
     */
    fun <A : Action?> findTransitivePrerequisitesOf(
        artifact: Artifact?, actionClass: Class<A?>, allowedArtifacts: Predicate<Artifact?>?
    ): MutableList<A?> {
        val actions: MutableList<A?> = ArrayList<A?>()
        val visited: MutableSet<Artifact?> = HashSet<Artifact?>()
        val toVisit: Queue<Artifact?> = ArrayDeque<Artifact?>()
        toVisit.add(artifact)
        while (!toVisit.isEmpty()) {
            val current: Artifact? = toVisit.remove()
            if (!visited.add(current)) {
                continue
            }
            val generatingAction: ActionAnalysisMetadata? = actionGraph.getGeneratingAction(current)
            if (generatingAction != null) {
                generatingAction.getInputs().toList().stream()
                    .filter(allowedArtifacts)
                    .forEach(toVisit::add)
                if (actionClass.isInstance(generatingAction)) {
                    actions.add(actionClass.cast(generatingAction))
                }
            }
        }
        return actions
    }

    fun <A : Action?> findTransitivePrerequisitesOf(
        artifact: Artifact?, actionClass: Class<A?>
    ): MutableList<A?> {
        return findTransitivePrerequisitesOf<A?>(artifact, actionClass, Predicates.alwaysTrue<Any?>())
    }

    /**
     * Looks in the given artifacts Iterable for the first Artifact whose path ends with the given
     * suffix and returns its generating Action.
     */
    fun getActionForArtifactEndingWith(artifacts: NestedSet<Artifact?>, suffix: String?): Action? {
        return getActionForArtifactEndingWith(artifacts.toList(), suffix)
    }

    /**
     * Looks in the given artifacts Iterable for the first Artifact whose path ends with the given
     * suffix and returns its generating Action.
     */
    fun getActionForArtifactEndingWith(artifacts: Iterable<Artifact?>, suffix: String?): Action? {
        val a: Artifact? = Companion.getFirstArtifactEndingWith(artifacts, suffix)

        if (a == null) {
            return null
        }

        val action: ActionAnalysisMetadata? = actionGraph.getGeneratingAction(a)
        if (action != null) {
            Preconditions.checkState(
                action is Action, "%s is not a proper Action object", action.prettyPrint()
            )
            return action as Action
        } else {
            return null
        }
    }

    /** Builder for a list of [MissDetail]s with defaults set to zero for all possible items.  */
    class MissDetailsBuilder {
        private val details: MutableMap<MissReason?, Int?> = EnumMap<Any?, Any?>(MissReason::class.java)

        /** Constructs a new builder with all possible cache miss reasons set to zero counts.  */
        init {
            for (reason in MissReason.values()) {
                if (reason === MissReason.UNRECOGNIZED) {
                    // The presence of this enum value is a protobuf artifact and not part of our metrics
                    // collection. Just skip it.
                    continue
                }
                details.put(reason, 0)
            }
        }

        /** Sets the count of the given miss reason to the given value.  */
        @CanIgnoreReturnValue
        fun set(reason: MissReason?, count: Int): MissDetailsBuilder {
            Preconditions.checkArgument(details.containsKey(reason))
            details.put(reason, count)
            return this
        }

        /** Constructs the list of [MissDetail]s.  */
        fun build(): Iterable<MissDetail?> {
            val result: MutableList<MissDetail?> = ArrayList<MissDetail?>(details.size())
            for (entry in details.entrySet()) {
                val detail: MissDetail? =
                    MissDetail.newBuilder().setReason(entry.getKey()).setCount(entry.getValue()).build()
                result.add(detail)
            }
            return result
        }
    }

    /**
     * An [ArtifactResolver] all of whose operations throw an exception.
     * 
     * 
     * This is to be used as a base class by other test programs that need to implement only a few
     * of the hooks required by the scenario under test.
     */
    class FakeArtifactResolverBase : ArtifactResolver {
        public override fun getSourceArtifact(
            execPath: PathFragment?,
            root: Root?,
            owner: ArtifactOwner?
        ): SourceArtifact? {
            throw UnsupportedOperationException()
        }

        public override fun getSourceArtifact(execPath: PathFragment?, root: Root?): SourceArtifact? {
            throw UnsupportedOperationException()
        }

        public override fun resolveSourceArtifact(
            execPath: PathFragment?, repositoryName: RepositoryName?
        ): SourceArtifact? {
            throw UnsupportedOperationException()
        }

        public override fun resolveSourceArtifactsAsciiCaseInsensitively(
            execPath: PathFragment?, repositoryName: RepositoryName?
        ): ImmutableList<SourceArtifact?>? {
            throw UnsupportedOperationException()
        }

        public override fun resolveSourceArtifacts(
            execPaths: Iterable<PathFragment?>?, resolver: PackageRootResolver?
        ): MutableMap<PathFragment?, SourceArtifact?>? {
            throw UnsupportedOperationException()
        }

        public override fun getPathFromSourceExecPath(execRoot: Path?, execPath: PathFragment?): Path? {
            throw UnsupportedOperationException()
        }

        public override fun isDerivedArtifact(execPath: PathFragment?): Boolean {
            throw UnsupportedOperationException()
        }
    }

    init {
        this.actionGraph = actionGraph
    }

    /**
     * A [OutputMetadataStore] all of whose operations throw an exception.
     * 
     * 
     * This is to be used as a base class by other test programs that need to implement only a few
     * of the hooks required by the scenario under test. Tests that need an instance but do not need
     * any functionality can use [.THROWING_METADATA_HANDLER].
     */
    open class FakeInputMetadataHandlerBase

        : InputMetadataProvider, OutputMetadataStore {
        @Throws(IOException::class)
        public override fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue? {
            throw UnsupportedOperationException()
        }

        public override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
            throw UnsupportedOperationException()
        }

        public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
            throw UnsupportedOperationException()
        }

        public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
            throw UnsupportedOperationException()
        }

        public override fun getFilesets(): MutableMap<Artifact?, FilesetOutputTree?>? {
            throw UnsupportedOperationException()
        }

        public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
            throw UnsupportedOperationException()
        }

        public override fun getRunfilesTrees(): ImmutableList<RunfilesTree?>? {
            throw UnsupportedOperationException()
        }

        @Throws(IOException::class, InterruptedException::class)
        public override fun getOutputMetadata(artifact: Artifact?): FileArtifactValue? {
            throw UnsupportedOperationException()
        }

        public override fun getInput(execPath: PathFragment?): ActionInput? {
            throw UnsupportedOperationException()
        }

        @Throws(IOException::class, InterruptedException::class)
        public override fun getTreeArtifactValue(treeArtifact: SpecialArtifact?): TreeArtifactValue? {
            throw UnsupportedOperationException()
        }

        public override fun injectFile(output: Artifact?, metadata: FileArtifactValue?) {
            throw UnsupportedOperationException()
        }

        public override fun injectTree(treeArtifact: SpecialArtifact?, tree: TreeArtifactValue?) {
            throw UnsupportedOperationException()
        }

        public override fun markOmitted(output: Artifact?) {
            throw UnsupportedOperationException()
        }

        public override fun artifactOmitted(artifact: Artifact?): Boolean {
            return false
        }

        public override fun resetOutputs(outputs: Iterable<out Artifact?>?) {
            throw UnsupportedOperationException()
        }
    }

    private class SimpleActionLookupKey(name: String?) : ActionLookupKey {
        private val name: String?

        init {
            this.name = name
        }

        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.createHermetic(name)
        }

        public override fun getLabel(): Label? {
            return null
        }

        public override fun getConfigurationKey(): BuildConfigurationKey? {
            return null
        }
    }

    companion object {
        val NULL_LABEL: Label? = Label.parseCanonicalUnchecked("//null/action:owner")

        val YET_ANOTHER_NULL_LABEL: Label? = Label.parseCanonicalUnchecked("//yet/another/null/action:owner")

        fun createContext(
            executor: Executor?,
            eventHandler: ExtendedEventHandler?,
            actionKeyContext: ActionKeyContext?,
            fileOutErr: FileOutErr?,
            execRoot: Path,
            outputMetadataStore: OutputMetadataStore?
        ): ActionExecutionContext {
            return createContext(
                executor,
                eventHandler,
                actionKeyContext,
                fileOutErr,
                SingleBuildFileCache(
                    execRoot.getPathString(),
                    PathFragment.create("dummy-output-path"),
                    execRoot.getFileSystem(),
                    SyscallCache.NO_CACHE
                ),
                outputMetadataStore,  /* clientEnv= */
                ImmutableMap.of<String?, String?>()
            )
        }

        fun createContext(
            executor: Executor?,
            eventHandler: ExtendedEventHandler?,
            actionKeyContext: ActionKeyContext?,
            fileOutErr: FileOutErr?,
            inputMetadataProvider: InputMetadataProvider?,
            outputMetadataStore: OutputMetadataStore?,
            clientEnv: MutableMap<String?, String?>?
        ): ActionExecutionContext {
            return ActionExecutionContext(
                executor,
                inputMetadataProvider,
                ActionInputPrefetcher.NONE,
                actionKeyContext,
                outputMetadataStore,  /* rewindingEnabled= */
                false,
                LostInputsCheck.NONE,
                fileOutErr,
                eventHandler,
                ImmutableMap.< K, V > copyOf<K?, V?>(clientEnv),  /* actionFileSystem= */
                null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE
            )
        }

        fun createContext(eventHandler: ExtendedEventHandler?): ActionExecutionContext {
            return createContext(DummyExecutor(), eventHandler)
        }

        fun createContextForFileWriteAction(
            eventHandler: ExtendedEventHandler?
        ): ActionExecutionContext {
            return createContext(
                DummyExecutor(),
                eventHandler,
                ActionKeyContext(),
                null,
                FakeActionInputFileCache(),
                null,
                ImmutableMap.of<String?, String?>()
            )
        }

        fun createContext(
            executor: Executor?, eventHandler: ExtendedEventHandler?
        ): ActionExecutionContext {
            return ActionExecutionContext(
                executor,  /* inputMetadataProvider= */
                null,
                ActionInputPrefetcher.NONE,
                ActionKeyContext(),  /* outputMetadataStore= */
                null,  /* rewindingEnabled= */
                false,
                LostInputsCheck.NONE,  /* fileOutErr= */
                null,
                eventHandler,  /* clientEnv= */
                ImmutableMap.of<K?, V?>(),  /* actionFileSystem= */
                null,
                DiscoveredModulesPruner.DEFAULT,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE
            )
        }

        fun createContextForInputDiscovery(
            executor: Executor?,
            eventHandler: ExtendedEventHandler?,
            actionKeyContext: ActionKeyContext?,
            fileOutErr: FileOutErr?,
            execRoot: Path,
            environment: Environment?,
            discoveredModulesPruner: DiscoveredModulesPruner?
        ): ActionExecutionContext {
            return ActionExecutionContext.forInputDiscovery(
                executor,
                SingleBuildFileCache(
                    execRoot.getPathString(),
                    PathFragment.create("dummy-output-path"),
                    execRoot.getFileSystem(),
                    SyscallCache.NO_CACHE
                ),
                ActionInputPrefetcher.NONE,
                actionKeyContext,  /* rewindingEnabled= */
                false,
                LostInputsCheck.NONE,
                fileOutErr,
                eventHandler,
                ImmutableMap.of<K?, V?>(),
                environment,  /* actionFileSystem= */
                null,
                discoveredModulesPruner,
                SyscallCache.NO_CACHE,
                ThreadStateReceiver.NULL_INSTANCE,  /* fileSystemSupportsInputDiscovery= */
                true
            )
        }

        /** Creates an [ActionExecutionValue] with only file outputs.  */
        fun createActionExecutionValue(
            artifactData: ImmutableMap<Artifact?, FileArtifactValue?>?
        ): ActionExecutionValue {
            return createActionExecutionValue(
                artifactData,  /* treeArtifactData= */
                ImmutableMap.of<Artifact?, TreeArtifactValue?>()
            )
        }

        /** Creates an [ActionExecutionValue] with only file and tree artifact outputs.  */
        fun createActionExecutionValue(
            artifactData: ImmutableMap<Artifact?, FileArtifactValue?>?,
            treeArtifactData: ImmutableMap<Artifact?, TreeArtifactValue?>?
        ): ActionExecutionValue {
            return ActionExecutionValue.create(
                artifactData,
                treeArtifactData,  /* richArtifactData= */
                null,  /* discoveredModules= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        }

        fun createArtifact(root: ArtifactRoot, path: Path?): Artifact? {
            return createArtifactWithRootRelativePath(root, root.getRoot().relativize(path))
        }

        fun createArtifact(root: ArtifactRoot, path: String?): Artifact? {
            return createArtifactWithRootRelativePath(root, PathFragment.create(path))
        }

        fun createArtifactWithRootRelativePath(
            root: ArtifactRoot, rootRelativePath: PathFragment?
        ): Artifact? {
            val execPath: PathFragment? = root.getExecPath().getRelative(rootRelativePath)
            return createArtifactWithExecPath(root, execPath)
        }

        fun createArtifactWithExecPath(root: ArtifactRoot, execPath: PathFragment?): Artifact? {
            return if (root.isSourceRoot())
                SourceArtifact(root, execPath, ArtifactOwner.NULL_OWNER)
            else
                DerivedArtifact.create(root, execPath, NULL_ARTIFACT_OWNER)
        }

        fun createRunfilesArtifact(root: ArtifactRoot?, execPath: String?): SpecialArtifact {
            return SpecialArtifact.create(
                root, PathFragment.create(execPath), NULL_ARTIFACT_OWNER, SpecialArtifactType.RUNFILES
            )
        }

        fun createFilesetArtifact(root: ArtifactRoot?, execPath: String?): SpecialArtifact {
            return SpecialArtifact.create(
                root, PathFragment.create(execPath), NULL_ARTIFACT_OWNER, SpecialArtifactType.FILESET
            )
        }

        fun createTreeArtifactWithGeneratingAction(
            root: ArtifactRoot?, execPath: PathFragment?
        ): SpecialArtifact {
            val treeArtifact: SpecialArtifact =
                SpecialArtifact.create(root, execPath, NULL_ARTIFACT_OWNER, SpecialArtifactType.TREE)
            treeArtifact.setGeneratingActionKey(NULL_ACTION_LOOKUP_DATA)
            return treeArtifact
        }

        fun createTreeArtifactWithGeneratingAction(
            root: ArtifactRoot, rootRelativePath: String?
        ): SpecialArtifact? {
            return createTreeArtifactWithGeneratingAction(
                root, root.getExecPath().getRelative(rootRelativePath)
            )
        }

        fun createUnresolvedSymlinkArtifact(
            root: ArtifactRoot, execPath: String?
        ): SpecialArtifact {
            return createUnresolvedSymlinkArtifactWithExecPath(
                root, root.getExecPath().getRelative(execPath)
            )
        }

        fun createUnresolvedSymlinkArtifactWithExecPath(
            root: ArtifactRoot?, execPath: PathFragment?
        ): SpecialArtifact {
            return SpecialArtifact.create(
                root, execPath, NULL_ARTIFACT_OWNER, SpecialArtifactType.UNRESOLVED_SYMLINK
            )
        }

        fun assertNoArtifactEndingWith(target: RuleConfiguredTarget, path: String?) {
            val endPattern = Pattern.compile(path + "$")
            for (action in target.getActions()) {
                for (output in action.getOutputs()) {
                    assertThat(output.getExecPathString()).doesNotMatch(endPattern)
                }
            }
        }

        fun createArtifactRootFromTwoPaths(root: Path?, execPath: Path): ArtifactRoot {
            return ArtifactRoot.asDerivedRoot(root, RootType.OUTPUT, execPath.relativeTo(root))
        }

        /**
         * Creates a [VirtualActionInput] with given string as contents and provided relative path.
         */
        @kotlin.jvm.JvmStatic
        fun createVirtualActionInput(relativePath: String?, contents: String?): VirtualActionInput? {
            return createVirtualActionInput(PathFragment.create(relativePath), contents)
        }

        /** Creates a [VirtualActionInput] with given string as contents and provided path.  */
        fun createVirtualActionInput(path: PathFragment, contents: String): VirtualActionInput {
            return object : VirtualActionInput() {
                public override fun getExecPathString(): String {
                    return path.getPathString()
                }

                public override fun getExecPath(): PathFragment {
                    return path
                }

                @Throws(IOException::class)
                public override fun writeTo(out: OutputStream) {
                    out.write(contents.getBytes(StandardCharsets.UTF_8))
                }
            }
        }

        @kotlin.jvm.JvmField
        @SerializationConstant
        val NULL_ARTIFACT_OWNER: ActionLookupKey = object : ActionLookupKey() {
            public override fun functionName(): SkyFunctionName? {
                return null
            }

            public override fun getLabel(): Label? {
                return NULL_LABEL
            }

            public override fun getConfigurationKey(): BuildConfigurationKey? {
                return null
            }

            public override fun toString(): String {
                return "NULL_ARTIFACT_OWNER"
            }
        }

        @kotlin.jvm.JvmField
        @SerializationConstant
        val YET_ANOTHER_NULL_ARTIFACT_OWNER: ActionLookupKey = object : ActionLookupKey() {
            public override fun functionName(): SkyFunctionName? {
                return null
            }

            public override fun getLabel(): Label? {
                return YET_ANOTHER_NULL_LABEL
            }

            public override fun getConfigurationKey(): BuildConfigurationKey? {
                return null
            }

            public override fun toString(): String {
                return "YET_ANOTHER_NULL_ARTIFACT_OWNER"
            }
        }

        @kotlin.jvm.JvmField
        val NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER: ActionTemplateExpansionKey? = ActionTemplateExpansionValue.key(
            NULL_ARTIFACT_OWNER,  /* actionIndex= */0
        )

        @SerializationConstant
        val DUMMY_ARTIFACT_FILE_SYSTEM: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

        @kotlin.jvm.JvmField
        val DUMMY_ARTIFACT: Artifact = SourceArtifact(
            ArtifactRoot.asSourceRoot(Root.absoluteRoot(DUMMY_ARTIFACT_FILE_SYSTEM)),
            PathFragment.create("/dummy"),
            NULL_ARTIFACT_OWNER
        )

        @kotlin.jvm.JvmField
        val NULL_ACTION_OWNER: ActionOwner? = ActionOwner.createDummy(
            NULL_LABEL,
            Location("dummy-file", 0, 0),  /* targetKind= */
            "dummy-kind",  /* buildConfigurationMnemonic= */
            "dummy-configuration-mnemonic",  /* configurationChecksum= */
            "dummy-configuration",
            BuildConfigurationEvent(
                BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                BuildEventStreamProtos.BuildEvent.getDefaultInstance()
            ),  /* isToolConfiguration= */
            false,  /* executionPlatform= */
            PlatformInfo.EMPTY_PLATFORM_INFO,  /* aspectDescriptors= */
            ImmutableList.of<E?>(),  /* execProperties= */
            ImmutableMap.of<K?, V?>()
        )

        @kotlin.jvm.JvmField
        @SerializationConstant
        val NULL_ACTION_LOOKUP_DATA: ActionLookupData? = ActionLookupData.create(NULL_ARTIFACT_OWNER, 0)

        @kotlin.jvm.JvmField
        @SerializationConstant
        val YET_ANOTHER_NULL_ACTION_LOOKUP_DATA: ActionLookupData? = ActionLookupData.create(
            YET_ANOTHER_NULL_ARTIFACT_OWNER, 0
        )

        /**
         * For a bunch of actions, gets the basenames of the paths and accumulates them in a space
         * separated string, like `foo.o bar.o baz.a`.
         */
        fun baseNamesOf(artifacts: NestedSet<Artifact?>): String? {
            return baseNamesOf(artifacts.toList())
        }

        /**
         * For a bunch of actions, gets the basenames of the paths and accumulates them in a space
         * separated string, like `foo.o bar.o baz.a`.
         */
        fun baseNamesOf(artifacts: Iterable<Artifact?>?): String {
            val baseNames = baseArtifactNames(artifacts)
            return Joiner.on(' ').join(baseNames)
        }

        /**
         * For a bunch of actions, gets the basenames of the paths, sorts them in alphabetical order and
         * accumulates them in a space separated string, for example `bar.o baz.a foo.o`.
         */
        fun sortedBaseNamesOf(artifacts: NestedSet<Artifact?>): String? {
            return sortedBaseNamesOf(artifacts.toList())
        }

        /**
         * For a bunch of actions, gets the basenames of the paths, sorts them in alphabetical order and
         * accumulates them in a space separated string, for example `bar.o baz.a foo.o`.
         */
        fun sortedBaseNamesOf(artifacts: Iterable<Artifact?>?): String {
            val baseNames = baseArtifactNames(artifacts)
            Collections.sort<String?>(baseNames)
            return Joiner.on(' ').join(baseNames)
        }

        /** For a bunch of artifacts, gets the basenames and accumulates them in a List.  */
        fun baseArtifactNames(artifacts: NestedSet<Artifact?>): MutableList<String?> {
            return Companion.transform<T?, R?>(
                artifacts.toList(),
                Function { artifact: T? -> artifact.getExecPath().getBaseName() })
        }

        /** For a bunch of artifacts, gets the basenames and accumulates them in a List.  */
        fun baseArtifactNames(artifacts: Iterable<out ActionInput?>): MutableList<String?> {
            return Companion.transform(artifacts) { artifact: ActionInput? -> artifact.getExecPath().getBaseName() }
        }

        /** For a bunch of artifacts, gets the exec paths and accumulates them in a List.  */
        fun execPaths(artifacts: NestedSet<Artifact?>): MutableList<String?>? {
            return execPaths(artifacts.toList())
        }

        /** For a bunch of artifacts, gets the exec paths and accumulates them in a List.  */
        fun execPaths(artifacts: Iterable<Artifact?>): MutableList<String?> {
            return Companion.transform<Artifact?, String?>(artifacts, Artifact::getExecPathString)
        }

        /**
         * For a bunch of artifacts, gets the pretty printed names and accumulates them in a List. Note
         * that this returns the root-relative paths, not the exec paths.
         */
        fun prettyArtifactNames(artifacts: NestedSet<Artifact?>): MutableList<String?>? {
            return prettyArtifactNames(artifacts.toList())
        }

        /**
         * For a bunch of artifacts, gets the pretty printed names and accumulates them in a List. Note
         * that this returns the root-relative paths, not the exec paths.
         */
        fun prettyArtifactNames(artifacts: Iterable<Artifact?>): MutableList<String?> {
            return Companion.transform<Artifact?, String?>(artifacts, Artifact::prettyPrint)
        }

        fun <T, R> transform(iterable: Iterable<T?>, mapper: Function<T?, R?>?): MutableList<R?> {
            // Can not use com.google.common.collect.Iterables.transform() there, as it returns Iterable.
            return Streams.stream<T?>(iterable).map<R?>(mapper).collect(Collectors.toList())
        }

        /** Returns a predicate to match [Artifact]s with the given root-relative path suffix.  */
        fun getArtifactSuffixMatcher(suffix: String?): Predicate<Artifact?> {
            return Predicate { input: Artifact? -> input.getRootRelativePath().getPathString().endsWith(suffix) }
        }

        /** Returns the first artifact found in the given set whose path ends with the given suffix.  */
        fun getFirstArtifactEndingWith(
            artifacts: NestedSet<out Artifact?>, suffix: String?
        ): Artifact? {
            return getFirstArtifactEndingWith(artifacts.toList(), suffix)
        }

        /**
         * Returns the first artifact found in the given Iterable whose path ends with the given suffix.
         */
        fun getFirstArtifactEndingWith(
            artifacts: Iterable<out Artifact?>, suffix: String?
        ): Artifact? {
            return getFirstArtifactMatching(
                artifacts, Predicate { artifact: Artifact? -> artifact.getExecPath().getPathString().endsWith(suffix) })
        }

        fun getFirstDerivedArtifactEndingWith(
            artifacts: NestedSet<out Artifact?>, suffix: String?
        ): Artifact? {
            return getFirstArtifactMatching(
                artifacts.toList(),
                Predicate { artifact: Artifact? ->
                    artifact is DerivedArtifact
                            && artifact.getExecPath().getPathString().endsWith(suffix)
                })
        }

        /** Returns the first Artifact in the provided Iterable that matches the specified predicate.  */
        fun getFirstArtifactMatching(
            artifacts: Iterable<out Artifact?>, predicate: Predicate<Artifact?>
        ): Artifact? {
            for (a in artifacts) {
                if (predicate.test(a)) {
                    return a
                }
            }
            return null
        }

        /**
         * Returns a list of the Artifacts in `artifacts` whose paths end with the given
         * suffix.
         */
        fun getArtifactsEndingWith(
            artifacts: Iterable<out Artifact>, suffix: String?
        ): MutableList<Artifact?> {
            val result: MutableList<Artifact?> = ArrayList<Artifact?>()
            for (a in artifacts) {
                if (a.getExecPath().getPathString().endsWith(suffix)) {
                    result.add(a)
                }
            }
            return result
        }

        /**
         * Returns the first artifact which is an input to "action" and has the specified basename. An
         * assertion error is raised if none is found.
         */
        fun getInput(action: ActionAnalysisMetadata, basename: String?): Artifact {
            for (artifact in action.getInputs().toList()) {
                if (artifact.getExecPath().getBaseName().equals(basename)) {
                    return artifact
                }
            }

            throw AssertionError("No input with basename '" + basename + "' in action " + action)
        }

        /** Returns true if an artifact that is an input to "action" with the specific basename exists.  */
        fun hasInput(action: ActionAnalysisMetadata, basename: String?): Boolean {
            try {
                getInput(action, basename)
                return true
            } catch (e: AssertionError) {
                return false
            }
        }

        /**
         * Returns the first artifact which is an output of "action" and has the specified basename. An
         * assertion error is raised if none is found.
         */
        fun getOutput(action: ActionAnalysisMetadata, basename: String?): Artifact {
            for (artifact in action.getOutputs()) {
                if (artifact.getExecPath().getBaseName().equals(basename)) {
                    return artifact
                }
            }
            throw AssertionError("No output with basename '" + basename + "' in action " + action)
        }

        fun createDummySpawnActionTemplate(
            inputTreeArtifact: SpecialArtifact?, outputTreeArtifact: SpecialArtifact?
        ): SpawnActionTemplate {
            return Builder(inputTreeArtifact, outputTreeArtifact)
                .setCommandLineTemplate(CustomCommandLine.builder().build())
                .setExecutable(PathFragment.create("bin/executable"))
                .setOutputPathMapper(TreeFileArtifact::getParentRelativePath)
                .build(NULL_ACTION_OWNER)
        }

        /**
         * A [OutputMetadataStore] for tests that throws [UnsupportedOperationException] for
         * its operations.
         */
        val THROWING_METADATA_HANDLER: OutputMetadataStore = object : FakeInputMetadataHandlerBase() {
            override fun toString(): String {
                return "THROWING_METADATA_HANDLER"
            }
        }

        /**
         * Ensures the special, meaningless, `memoizedIsInitialized` field in [ActionOwner] is set.
         * 
         * 
         * This field is set upon serializing a proto. It's intended to memoize checking that all the
         * required fields are set. Since the protos in question are proto3, there are no required fields
         * so the field is meaningless. However, serialization tests sometimes use reflection to compare
         * the round tripped output to the input.
         * 
         * 
         * In particular, [BuildConfigurationEvent] contains a couple of instances of this field.
         */
        fun ensureMemoizedIsInitializedIsSet(action: ActionAnalysisMetadata) {
            val buildConfigurationEvent: BuildConfigurationEvent =
                action.getOwner().getBuildConfigurationEvent()
            assertThat(buildConfigurationEvent.getEventId().isInitialized()).isTrue()
            assertThat(buildConfigurationEvent.asStreamProto( /* unusedConverters= */null).isInitialized())
                .isTrue()
        }

        @kotlin.jvm.JvmStatic
        fun createActionLookupKey(name: String?): ActionLookupKey {
            return SimpleActionLookupKey(name)
        }
    }
}
