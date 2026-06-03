// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Utilities for finding [ActionInput] instances within a [Spawn].  */
object SpawnInputUtils {
    fun getInputWithName(spawn: Spawn, name: String?): ActionInput {
        return spawn.getInputFiles().toList().stream()
            .filter({ input -> input.getExecPathString().contains(name) })
            .findFirst()
            .orElseThrow({ noSuchInput("spawn input", name, spawn) })
    }

    fun getFilesetInputWithName(
        spawn: Spawn,
        inputMetadataProvider: InputMetadataProvider,
        artifactName: String?,
        inputName: String?
    ): ActionInput {
        for (actionInput in spawn.getInputFiles().toList()) {
            if ((actionInput !is Artifact) || !actionInput.isFileset() || !actionInput.getExecPathString()
                    .contains(artifactName)
            ) {
                continue
            }
            for (filesetOutputSymlink in inputMetadataProvider.getFileset(actionInput).symlinks()) {
                if (filesetOutputSymlink.target().getExecPathString().contains(inputName)) {
                    return filesetOutputSymlink.target()
                }
            }
        }
        throw noSuchInput("fileset input in " + artifactName, inputName, spawn)
    }

    fun getRunfilesFilesetInputWithName(
        spawn: Spawn, context: ActionExecutionContext, artifactName: String?, inputName: String?
    ): Artifact {
        val filesetArtifact: Artifact = getRunfilesArtifactWithName(spawn, context, artifactName)
        checkState(filesetArtifact.isFileset(), filesetArtifact)

        val filesetLinks: com.google.common.collect.ImmutableList<FilesetOutputSymlink> =
            context.getInputMetadataProvider().getFileset(filesetArtifact).symlinks()
        for (filesetOutputSymlink in filesetLinks) {
            if (filesetOutputSymlink.target().getExecPathString().contains(inputName)) {
                return filesetOutputSymlink.target()
            }
        }
        throw noSuchInput("runfiles fileset in " + filesetArtifact, inputName, spawn)
    }

    fun getTreeArtifactWithName(spawn: Spawn, name: String?): SpecialArtifact? {
        val input: ActionInput = getInputWithName(spawn, name)
        checkState(
            input is SpecialArtifact && (input as SpecialArtifact).isTreeArtifact(),
            "Expected spawn %s to have tree artifact input with name %s, but it is: %s",
            spawn.getResourceOwner().describe(),
            name,
            input
        )
        return input as SpecialArtifact?
    }

    fun getExpandedToArtifact(
        name: String?, expandableArtifact: Artifact?, spawn: Spawn, context: ActionExecutionContext
    ): Artifact {
        return context
            .getInputMetadataProvider()
            .getTreeMetadata(expandableArtifact)
            .getChildren()
            .stream()
            .filter({ artifact -> artifact.getExecPathString().contains(name) })
            .findFirst()
            .orElseThrow(
                { noSuchInput("artifact expanded from " + expandableArtifact, name, spawn) })
    }

    fun getRunfilesArtifactWithName(
        spawn: Spawn, context: ActionExecutionContext, name: String?
    ): Artifact {
        return spawn.getInputFiles().toList().stream()
            .filter({ i -> i is Artifact && (i as Artifact).isRunfilesTree() })
            .map({ i -> context.getInputMetadataProvider().getRunfilesMetadata(i).getRunfilesTree() })
            .flatMap({ t -> t.getArtifacts().toList().stream() })
            .filter({ artifact -> artifact.getExecPathString().contains(name) })
            .findFirst()
            .orElseThrow({ noSuchInput("runfiles artifact", name, spawn) })
    }

    private fun noSuchInput(inputType: String?, name: String?, spawn: Spawn): java.util.NoSuchElementException {
        val action: ActionExecutionMetadata = spawn.getResourceOwner()
        return java.util.NoSuchElementException(
            String.format(
                "No %s named %s in %s",
                inputType,
                name,
                com.google.common.base.MoreObjects.firstNonNull<T?>(action.getProgressMessage(), action.prettyPrint())
            )
        )
    }
}
