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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.AbstractAction

/** Context object to be passed to the implementation of a ctx.actions.map_directory().  */
class StarlarkTemplateContext(
    semantics: StarlarkSemantics?,
    actionOwner: ActionOwner?,
    artifactOwner: ActionLookupKey?,
    spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder,
    repoMappingSupplier: InterruptibleSupplier<RepositoryMapping?>?,
    outputDirectories: com.google.common.collect.ImmutableSet<SpecialArtifact?>,
    executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
) : StarlarkTemplateContextApi {
    private val semantics: StarlarkSemantics?
    private val actionOwner: ActionOwner?
    private val artifactOwner: ActionLookupKey?
    private val spawnActionBuilder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder
    private val repoMappingSupplier: InterruptibleSupplier<RepositoryMapping?>?
    private val outputDirectories: com.google.common.collect.ImmutableSet<SpecialArtifact?>
    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
    private var actions: com.google.common.collect.ImmutableList.Builder<AbstractAction?>? =
        com.google.common.collect.ImmutableList.builder<AbstractAction?>()

    init {
        this.semantics = semantics
        this.actionOwner = actionOwner
        this.artifactOwner = artifactOwner
        this.spawnActionBuilder = spawnActionBuilder
        this.repoMappingSupplier = repoMappingSupplier
        this.outputDirectories = outputDirectories
        this.executionInfo = executionInfo
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun run(
        outputs: net.starlark.java.eval.Sequence<*>?,
        inputs: Any,
        executableUnchecked: Any,
        toolsUnchecked: Any?,
        arguments: net.starlark.java.eval.Sequence<*>,
        progressMessage: Any?
    ) {
        val builder: com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder =
            newSpawnActionBuilder().addOutputs(
                net.starlark.java.eval.Sequence.cast<Artifact?>(
                    outputs,
                    Artifact::class.java,
                    "outputs"
                )
            )

        // The only other type is NoneType, which if specified will use the default progress message of
        // an action.
        if (progressMessage is String) {
            builder.setProgressMessageFromStarlark(progressMessage)
        }

        StarlarkActionFactory.Companion.buildCommandLine(builder, arguments, repoMappingSupplier)

        val inputArtifacts: MutableList<Artifact?>
        when (inputs) {
            -> {
                inputArtifacts = net.starlark.java.eval.Sequence.cast<Artifact?>(inputs, Artifact::class.java, "inputs")
                builder.addInputs(inputArtifacts)
            }

            -> {
                val inputNestedSet: NestedSet<Artifact?> = Depset.cast(depset, Artifact::class.java, "inputs")
                inputArtifacts = inputNestedSet.toList()
                builder.addTransitiveInputs(inputNestedSet)
            }

            else -> {
                throw Starlark.errorf("Expected a list or depset but got %s", Starlark.type(inputs))
            }
        }

        for (input in inputArtifacts) {
            if (outputDirectories.contains(input)) {
                throw Starlark.errorf(
                    "Output directory %s cannot be used as an input to template_ctx.run()", input
                )
            }
        }

        when (executableUnchecked) {
            -> builder.setExecutable(executable)
            -> builder.setExecutable(filesToRun)
            else -> {
                throw Starlark.errorf(
                    "Expected a File or FilesToRunProvider but got %s", Starlark.type(executableUnchecked)
                )
            }
        }

        if (toolsUnchecked !== Starlark.NONE) {
            val tools: MutableList<*> =
                when (toolsUnchecked) {
                    -> net.starlark.java.eval.Sequence.cast<Any?>(toolsUnchecked, Any::class.java, "tools")
                    -> Depset.cast(depset, Artifact::class.java, "tools").toList()
                    else -> throw Starlark.errorf(
                        "Expected a list or depset but got %s", Starlark.type(toolsUnchecked)
                    )
                }
            for (toolUnchecked in tools) {
                when (toolUnchecked) {
                    -> builder.addTool(artifact)
                    -> builder.addTransitiveTools(filesToRun.getFilesToRun())
                    -> builder.addTransitiveTools(Depset.cast(depset, Artifact::class.java, "tools"))
                    else -> {
                        throw Starlark.errorf(
                            "Expected a File, FilesToRunProvider or Depset but got %s",
                            Starlark.type(toolUnchecked)
                        )
                    }
                }
            }
        }

        actions.add(builder.buildForStarlarkActionTemplate(actionOwner))
    }

    fun registerAction(action: AbstractAction) {
        actions.add(action)
    }

    private fun newSpawnActionBuilder(): com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder {
        return com.google.devtools.build.lib.analysis.actions.SpawnAction.Builder(spawnActionBuilder)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun declareFile(filename: String?, directory: FileApi?): Artifact {
        if (!outputDirectories.contains(directory)) {
            throw Starlark.errorf(
                "Cannot declare file `%s` in non-output directory %s", filename, directory
            )
        }

        return TreeFileArtifact.createTemplateExpansionOutput(
            SpecialArtifact.cast(directory, SpecialArtifactType.TREE, "directory"),
            PathFragment.create(filename),
            artifactOwner
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun declareSubdirectory(subdirectory: String?, directory: FileApi?): Artifact {
        val parent: SpecialArtifact = SpecialArtifact.cast(directory, SpecialArtifactType.TREE, "directory")
        // We do not support nesting subtrees in subtrees.
        if (parent.isSubTreeArtifact()) {
            throw Starlark.errorf(
                "Cannot declare subdirectory `%s` in another subdirectory %s", subdirectory, directory
            )
        }
        if (!outputDirectories.contains(parent)) {
            throw Starlark.errorf(
                "Cannot declare subdirectory `%s` in non-output directory %s", subdirectory, directory
            )
        }
        return SpecialArtifact.createSubTreeArtifact(
            parent, PathFragment.create(subdirectory), artifactOwner
        )
    }

    public override fun args(thread: StarlarkThread): Args {
        return Args.newArgs(thread.mutability(), semantics)
    }

    fun getActions(): com.google.common.collect.ImmutableList<AbstractAction?> {
        return actions.build()
    }

    fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return executionInfo
    }

    fun getActionOwner(): ActionOwner? {
        return actionOwner
    }

    fun close() {
        actions = null
    }
}
