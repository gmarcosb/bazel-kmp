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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Action

/** A value representing an executed action.  */
@Immutable
@ThreadSafe
abstract class ActionExecutionValue private constructor() : SkyValue {
    /**
     * Retrieves a [FileArtifactValue] for a regular (non-tree) derived artifact.
     * 
     * 
     * The value for the given artifact must be present, or else [NullPointerException] will
     * be thrown.
     */
    open fun getExistingFileArtifactValue(artifact: Artifact): FileArtifactValue? {
        com.google.common.base.Preconditions.checkArgument(
            artifact is DerivedArtifact && !artifact.isTreeArtifact(),
            "Cannot request %s from %s",
            artifact,
            this
        )

        val result: FileArtifactValue?
        if (artifact.isChildOfDeclaredDirectory()) {
            val tree: TreeArtifactValue? = getTreeArtifactValue(artifact.getParent())
            result = if (tree == null) null else tree.getChildValues().get(artifact)
        } else if (artifact is ArchivedTreeArtifact) {
            val tree: TreeArtifactValue? = getTreeArtifactValue(artifact.getParent())
            val archivedRepresentation: ArchivedRepresentation =
                tree.getArchivedRepresentation()
                    .orElseThrow<java.util.NoSuchElementException?>(
                        java.util.function.Supplier { java.util.NoSuchElementException("Missing archived representation in: " + tree) })
            checkArgument(
                archivedRepresentation.archivedTreeFileArtifact.equals(artifact),
                "Multiple archived tree artifacts for: %s",
                artifact.getParent()
            )
            result = archivedRepresentation.archivedFileValue
        } else {
            result = this.allFileValues.get(artifact)
        }

        return checkNotNull(
            result,
            "Missing artifact %s (generating action key %s) in %s",
            artifact,
            (artifact as DerivedArtifact).getGeneratingActionKey(),
            this
        )
    }

    open fun getTreeArtifactValue(artifact: Artifact): TreeArtifactValue? {
        checkArgument(artifact.isTreeArtifact(), artifact)
        return null
    }

    /**
     * Returns a map containing all artifacts output by the action, except for tree artifacts which
     * are accessible via [.getAllTreeArtifactValues].
     */
    @kotlin.jvm.JvmField
    abstract val allFileValues: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>

    open val allTreeArtifactValues: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>
        /** Returns a map containing all tree artifacts output by the action.  */
        get() = com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>()

    open val richArtifactData: RichArtifactData?
        get() = null

    open val discoveredModules: NestedSet<Artifact?>
        get() = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(
            com.google.devtools.build.lib.util.ClassName.getSimpleNameWithOuter(
                javaClass
            )
        )
            .add("files", this.allFileValues)
            .add("trees", this.allTreeArtifactValues)
            .toString()
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ActionExecutionValue) {
            return false
        }
        return this.allFileValues == obj.allFileValues
                && this.allTreeArtifactValues == obj.allTreeArtifactValues
                && this.richArtifactData == obj.richArtifactData // We use shallowEquals to avoid materializing the nested sets just for change-pruning. This
                // makes change-pruning potentially less effective, but never incorrect.
                && this.discoveredModules.shallowEquals(obj.discoveredModules)
    }

    override fun hashCode(): Int {
        return (31
                * HashCodes.hashObjects(
            this.allFileValues, this.allTreeArtifactValues, this.richArtifactData
        )
                + this.discoveredModules.shallowHashCode())
    }

    /**
     * Creates a new `ActionExecutionValue` by transforming this one's outputs so that artifact
     * owners match the given action's outputs.
     * 
     * 
     * The given action must be [ ][com.google.devtools.build.lib.actions.Actions.canBeShared] with the action that
     * originally produced this `ActionExecutionValue`.
     */
    @Throws(ActionTransformException::class)
    fun transformForSharedAction(action: Action): ActionExecutionValue {
        val artifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?> = this.allFileValues
        val treeArtifactData: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?> =
            this.allTreeArtifactValues
        val outputs: MutableCollection<Artifact?> = action.getOutputs()
        if (outputs.size != artifactData.size + treeArtifactData.size) {
            throw ActionTransformException("Cannot share %s with %s", this, action)
        }
        val newArtifactMap: com.google.common.collect.ImmutableMap<OwnerlessArtifactWrapper, Artifact> =
            com.google.common.collect.Maps.uniqueIndex(outputs, { OwnerlessArtifactWrapper() })
        return create(
            Companion.transformMap<FileArtifactValue?>(
                artifactData,
                newArtifactMap,
                action,
                java.util.function.BiFunction { newArtifact: Artifact?, value: FileArtifactValue? -> value }),
            Companion.transformMap<TreeArtifactValue?>(
                treeArtifactData,
                newArtifactMap,
                action,
                java.util.function.BiFunction { newArtifact: Artifact?, tree: TreeArtifactValue? ->
                    transformSharedTree(
                        newArtifact,
                        tree
                    )
                }),
            this.richArtifactData,  // Discovered modules come from the action's inputs, and so don't need to be transformed.
            this.discoveredModules
        )
    }

    /**
     * Exception thrown when [.transformForSharedAction] is called with an action that does not
     * have the same outputs.
     */
    class ActionTransformException @com.google.errorprone.annotations.FormatMethod private constructor(
        @com.google.errorprone.annotations.FormatString format: String,
        vararg args: Any?
    ) : java.lang.Exception(
        String.format(format, *args)
    )

    /**
     * The result of an action that outputs a single file (the common case). Optimizes for space by
     * storing the single artifact and value without the [ImmutableMap] wrapper.
     */
    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal open class SingleOutputFile(artifact: Artifact, value: FileArtifactValue) : ActionExecutionValue() {
        private val artifact: Artifact
        private val value: FileArtifactValue

        init {
            this.artifact = artifact
            this.value = value
        }

        // Override to avoid creating an ImmutableMap in the common case that the requested artifact is
        // correct. This bypasses the preconditions checks in super, but if the artifact is correct,
        // those would all pass anyway.
        override fun getExistingFileArtifactValue(artifact: Artifact): FileArtifactValue? {
            if (artifact.equals(this.artifact)) {
                return value
            }
            // This will throw an exception. Call super to make failure modes consistent.
            return super.getExistingFileArtifactValue(artifact)
        }

        override fun getAllFileValues(): com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?> {
            return com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>(artifact, value)
        }
    }

    /** The result of an action that produces rich data.  */
    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    class WithRichData internal constructor(
        artifact: Artifact,
        value: FileArtifactValue,
        richArtifactData: RichArtifactData?
    ) : SingleOutputFile(artifact, value) {
        private val richArtifactData: RichArtifactData?

        init {
            this.richArtifactData = richArtifactData
        }

        override fun getRichArtifactData(): RichArtifactData? {
            return richArtifactData
        }
    }

    /**
     * The result of a [com.google.devtools.build.lib.rules.cpp.CppCompileAction] that
     * [discovers modules][IncludeScannable.getDiscoveredModules].
     */
    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class ModuleDiscovering(
        artifact: Artifact,
        value: FileArtifactValue,
        discoveredModules: NestedSet<Artifact?>?
    ) : SingleOutputFile(artifact, value) {
        private val discoveredModules: NestedSet<Artifact?>?

        init {
            this.discoveredModules = discoveredModules
        }

        override fun getDiscoveredModules(): NestedSet<Artifact?>? {
            return discoveredModules
        }
    }

    /** The result of an action that outputs an arbitrary number of files.  */
    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal open class MultiOutputFile(artifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?) :
        ActionExecutionValue() {
        private val artifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?

        init {
            this.artifactData = artifactData
        }

        override fun getAllFileValues(): com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>? {
            return artifactData
        }
    }

    /** The result of an action that outputs a single tree artifact and no other files.  */
    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class SingleTree(treeArtifact: Artifact, treeValue: TreeArtifactValue) : ActionExecutionValue() {
        private val treeArtifact: Artifact
        private val treeValue: TreeArtifactValue

        init {
            this.treeArtifact = treeArtifact
            this.treeValue = treeValue
        }

        override fun getTreeArtifactValue(artifact: Artifact): TreeArtifactValue? {
            checkArgument(artifact.isTreeArtifact(), artifact)
            return if (artifact.equals(treeArtifact)) treeValue else null
        }

        override fun getAllTreeArtifactValues(): com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?> {
            return com.google.common.collect.ImmutableMap.of<Artifact?, TreeArtifactValue?>(treeArtifact, treeValue)
        }

        override fun getAllFileValues(): com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?> {
            return com.google.common.collect.ImmutableMap.of<Artifact?, FileArtifactValue?>()
        }
    }

    /**
     * The result of an action that outputs multiple tree artifacts or a combination of tree artifacts
     * and files.
     */
    @AutoCodec(deserializedInterface = DeserializedSkyValue::class)
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class MultiTree(
        artifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>?,
        treeArtifactData: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>
    ) : MultiOutputFile(artifactData) {
        private val treeArtifactData: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>

        init {
            this.treeArtifactData = treeArtifactData
        }

        override fun getTreeArtifactValue(artifact: Artifact): TreeArtifactValue? {
            checkArgument(artifact.isTreeArtifact(), artifact)
            return treeArtifactData.get(artifact)
        }

        override fun getAllTreeArtifactValues(): com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?> {
            return treeArtifactData
        }
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting // All non-test usage should go through createFromOutputMetadataStore().
        fun create(
            artifactData: com.google.common.collect.ImmutableMap<Artifact?, FileArtifactValue?>,
            treeArtifactData: com.google.common.collect.ImmutableMap<Artifact?, TreeArtifactValue?>,
            richArtifactData: RichArtifactData?,
            discoveredModules: NestedSet<Artifact?>
        ): ActionExecutionValue {
            // Use forEach instead of entrySet to avoid instantiating an EntrySet in ImmutableMap.
            artifactData.forEach { (artifact: Artifact?, value: FileArtifactValue?) ->
                com.google.common.base.Preconditions.checkArgument(
                    !artifact.isChildOfDeclaredDirectory(),
                    "%s should only be stored in a TreeArtifactValue",
                    artifact
                )
                com.google.common.base.Preconditions.checkArgument(
                    !value.getType().isFile() || value.getDigest() != null,
                    "Missing digest for %s",
                    artifact
                )
            }
            treeArtifactData.forEach { (tree: Artifact?, treeValue: TreeArtifactValue?) ->
                treeValue
                    .getChildValues()
                    .forEach { (child: TreeFileArtifact?, childValue: FileArtifactValue?) ->  // Ignore symlinks to directories, which don't have a digest.
                        com.google.common.base.Preconditions.checkArgument(
                            !childValue.getType().isFile() || childValue.getDigest() != null,
                            "Missing digest for file %s in tree artifact %s",
                            child,
                            tree
                        )
                    }
            }

            if (richArtifactData != null) {
                com.google.common.base.Preconditions.checkArgument(
                    artifactData.size == 1,
                    "actions with rich artifact data should have a single output file (the manifest): %s",
                    artifactData
                )
                com.google.common.base.Preconditions.checkArgument(
                    treeArtifactData.isEmpty(),
                    "actions with rich artifact data do not output tree artifacts: %s",
                    treeArtifactData
                )
                checkArgument(
                    discoveredModules.isEmpty(),
                    "actions with rich artifact data do not discover modules: %s",
                    discoveredModules
                )
                return WithRichData(
                    com.google.common.collect.Iterables.getOnlyElement<Artifact?>(artifactData.keys),
                    com.google.common.collect.Iterables.getOnlyElement<FileArtifactValue?>(artifactData.values),
                    richArtifactData
                )
            }

            if (!discoveredModules.isEmpty()) {
                com.google.common.base.Preconditions.checkArgument(
                    artifactData.size == 1,
                    "Module-discovering actions should have a single output file (the .pcm file): %s",
                    artifactData
                )
                com.google.common.base.Preconditions.checkArgument(
                    treeArtifactData.isEmpty(),
                    "Module-discovering actions do not output tree artifacts: %s",
                    treeArtifactData
                )
                return ModuleDiscovering(
                    com.google.common.collect.Iterables.getOnlyElement<Artifact?>(artifactData.keys),
                    com.google.common.collect.Iterables.getOnlyElement<FileArtifactValue?>(artifactData.values),
                    discoveredModules
                )
            }

            if (!treeArtifactData.isEmpty()) {
                return if (treeArtifactData.size == 1 && artifactData.isEmpty())
                    SingleTree(
                        com.google.common.collect.Iterables.getOnlyElement<Artifact?>(treeArtifactData.keys),
                        com.google.common.collect.Iterables.getOnlyElement<TreeArtifactValue?>(treeArtifactData.values)
                    )
                else
                    MultiTree(artifactData, treeArtifactData)
            }

            com.google.common.base.Preconditions.checkArgument(!artifactData.isEmpty(), "No outputs")
            return if (artifactData.size == 1)
                SingleOutputFile(
                    com.google.common.collect.Iterables.getOnlyElement<Artifact?>(artifactData.keys),
                    com.google.common.collect.Iterables.getOnlyElement<FileArtifactValue?>(artifactData.values)
                )
            else
                MultiOutputFile(artifactData)
        }

        fun create(
            actionOutputMetadataStore: ActionOutputMetadataStore,
            richArtifactData: RichArtifactData?,
            action: Action?
        ): ActionExecutionValue {
            return create(
                actionOutputMetadataStore.getAllArtifactData(),
                actionOutputMetadataStore.getAllTreeArtifactData(),
                richArtifactData,
                if (action is IncludeScannable)
                    action.discoveredModules
                else
                    NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )
        }

        @Throws(ActionTransformException::class)
        private fun <V> transformMap(
            data: com.google.common.collect.ImmutableMap<Artifact?, V?>,
            newArtifactMap: MutableMap<OwnerlessArtifactWrapper, Artifact>,
            action: Action?,
            transform: java.util.function.BiFunction<Artifact?, V?, V?>
        ): com.google.common.collect.ImmutableMap<Artifact?, V?> {
            if (data.isEmpty()) {
                return data
            }

            val result: com.google.common.collect.ImmutableMap.Builder<Artifact?, V?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<Artifact?, V?>(data.size)
            for (entry in data.entries) {
                val artifact: Artifact? = entry.key
                val newArtifact: Artifact = newArtifactMap.get(OwnerlessArtifactWrapper(artifact))
                if (newArtifact == null) {
                    throw ActionTransformException(
                        "No output matching %s, cannot share with %s", artifact, action
                    )
                }
                result.put(newArtifact, transform.apply(newArtifact, entry.value))
            }
            return result.buildOrThrow()
        }

        /** Transforms the children of a [TreeArtifactValue] so that owners are consistent.  */
        private fun transformSharedTree(
            newArtifact: Artifact, tree: TreeArtifactValue
        ): TreeArtifactValue? {
            checkState(newArtifact.isTreeArtifact(), "Expected tree artifact, got %s", newArtifact)

            val newParent: SpecialArtifact = newArtifact as SpecialArtifact
            val newTree: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(newParent)

            for (child in tree.getChildValues().entries) {
                newTree.putChild(
                    TreeFileArtifact.createTreeOutput(newParent, child.key.getParentRelativePath()),
                    child.value
                )
            }

            tree.getArchivedRepresentation()
                .ifPresent(
                    java.util.function.Consumer { archivedRepresentation: ArchivedRepresentation? ->
                        newTree.setArchivedRepresentation(
                            ArchivedTreeArtifact.createForTree(newParent),
                            archivedRepresentation.archivedFileValue
                        )
                    })

            return newTree.build()
        }
    }
}
