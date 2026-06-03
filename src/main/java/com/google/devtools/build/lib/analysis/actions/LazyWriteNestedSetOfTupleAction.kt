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
 * Lazily writes the content of a nested set of tuplesToWrite to an output file.
 * 
 * 
 * Writes delimiter separated Tuple elements to the output file.
 */
class LazyWriteNestedSetOfTupleAction(
    owner: ActionOwner?,
    output: Artifact?,
    tuplesToWrite: NestedSet<Tuple?>,
    delimiter: String?
) : AbstractFileWriteAction(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
    private val tuplesToWrite: NestedSet<Tuple?>
    private var fileContents: String? = null
    private val delimiter: String?

    init {
        this.tuplesToWrite = tuplesToWrite
        this.delimiter = delimiter
    }

    override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
        return DeterministicWriter { out -> out.write(StringUnsafe.getInternalStringBytes(getContents(delimiter))) }
    }

    /** Computes the Action key for this action by computing the fingerprint for the file contents.  */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint?
    ) {
        actionKeyContext.addNestedSetToFingerprint(fp, tuplesToWrite)
    }

    private fun getContents(delimiter: String?): String {
        if (fileContents == null) {
            val stringBuilder: java.lang.StringBuilder = java.lang.StringBuilder()
            for (tuple in tuplesToWrite.toList()) {
                if (tuple.isEmpty()) {
                    continue
                }
                stringBuilder.append(tuple.get(0))
                for (i in 1..<tuple.size()) {
                    stringBuilder.append(delimiter).append(tuple.get(i))
                }
                stringBuilder.append("\n")
            }
            fileContents = stringBuilder.toString()
        }
        return fileContents!!
    }
}
