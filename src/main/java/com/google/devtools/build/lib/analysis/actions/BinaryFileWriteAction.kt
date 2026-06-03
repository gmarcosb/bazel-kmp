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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Action to write a binary file.
 */
@Immutable // if source is immutable
class BinaryFileWriteAction(
    owner: ActionOwner?,
    output: Artifact?,
    source: com.google.common.io.ByteSource?,
    private val makeExecutable: Boolean
) : AbstractFileWriteAction(owner,  /* inputs= */NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
    private val source: com.google.common.io.ByteSource

    /**
     * Creates a new BinaryFileWriteAction instance without inputs.
     * 
     * @param owner the action owner.
     * @param output the Artifact that will be created by executing this Action.
     * @param source a source of bytes that will be written to the file.
     * @param makeExecutable iff true will change the output file to be executable.
     */
    init {
        this.source = com.google.common.base.Preconditions.checkNotNull<com.google.common.io.ByteSource>(source)
    }

    override fun makeExecutable(): Boolean {
        return makeExecutable
    }

    @com.google.common.annotations.VisibleForTesting
    fun getSource(): com.google.common.io.ByteSource {
        return source
    }

    override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
        return DeterministicWriter { out ->
            source.openStream().use { `in` ->
                com.google.common.io.ByteStreams.copy(`in`, out)
            }
            out.flush()
        }
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addBoolean(makeExecutable())

        try {
            source.openStream().use { `in` ->
                val buffer = ByteArray(512)
                var amountRead: Int
                while ((`in`.read(buffer).also { amountRead = it }) != -1) {
                    fp.addBytes(buffer, 0, amountRead)
                }
            }
        } catch (e: IOException) {
            throw java.lang.RuntimeException(e)
        }
    }

    companion object {
        private const val GUID = "eeee07fe-4b40-11e4-82d6-eba0b4f713e2"
    }
}
