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
package com.google.devtools.build.lib.analysis.actions


import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Lazily writes the path of the given files separated by newline into a specified output file.
 * 
 * 
 * By default the exec path is written but this behaviour can be customized by providing an
 * alternative converter function.
 */
class LazyWritePathsFileAction(
    owner: ActionOwner?,
    output: Artifact?,
    files: NestedSet<Artifact?>,
    filesToIgnore: com.google.common.collect.ImmutableSet<Artifact?>,
    includeDerivedArtifacts: Boolean
) : AbstractFileWriteAction(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
    private val files: NestedSet<Artifact?>
    private val filesToIgnore: com.google.common.collect.ImmutableSet<Artifact?>
    private val includeDerivedArtifacts: Boolean

    init {
        // We don't need to pass the given files as explicit inputs to this action; we don't care about
        // them, we only need their names, which we already know.
        this.files = files
        this.includeDerivedArtifacts = includeDerivedArtifacts
        this.filesToIgnore = filesToIgnore
    }

    override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
        return DeterministicWriter { out -> out.write(StringUnsafe.getInternalStringBytes(getContents())) }
    }

    /** Computes the Action key for this action by computing the fingerprint for the file contents.  */
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addBoolean(includeDerivedArtifacts)
        fp.addString(getContents())
    }

    private fun getContents(): String {
        val stringBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
        for (file in files.toList()) {
            if (filesToIgnore.contains(file)) {
                continue
            }
            if (file.isSourceArtifact() || includeDerivedArtifacts) {
                stringBuilder.append(file.getExecPathString())
                stringBuilder.append("\n")
            }
        }
        return stringBuilder.toString()
    }

    fun getFiles(): NestedSet<Artifact?> {
        return files
    }

    companion object {
        private const val GUID = "6be94d90-96f3-4bec-8104-1fb08abc2546"
    }
}
