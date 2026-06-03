// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Abstract Action to write to a file.
 */
abstract class AbstractFileWriteAction
/**
 * Creates a new AbstractFileWriteAction instance.
 * 
 * @param owner the action owner.
 * @param inputs the Artifacts that this Action depends on
 * @param output the Artifact that will be created by executing this Action.
 */
    (owner: ActionOwner?, inputs: NestedSet<Artifact?>?, output: Artifact) :
    AbstractAction(owner, inputs, com.google.common.collect.ImmutableSet.of<E?>(output)) {
    open fun makeExecutable(): Boolean {
        return false
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        try {
            val deterministicWriter: DeterministicWriter?
            Profiler.instance().profile("setupDeterministicWriter").use { c ->
                deterministicWriter = newDeterministicWriter(actionExecutionContext)
            }
            val context: FileWriteActionContext =
                actionExecutionContext.getContext(FileWriteActionContext::class.java)
            val result: com.google.common.collect.ImmutableList<SpawnResult?>? =
                context.writeOutputToFile(
                    this, actionExecutionContext, deterministicWriter, makeExecutable(), isRemotable()
                )
            afterWrite(actionExecutionContext)
            return ActionResult.create(result)
        } catch (e: ExecException) {
            throw ActionExecutionException.fromExecException(e, this)
        }
    }

    /**
     * Produce a DeterministicWriter that can write the file to an OutputStream deterministically.
     * 
     * @param ctx context for use with creating the writer.
     */
    @Throws(java.lang.InterruptedException::class, ExecException::class)
    abstract fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter?

    /**
     * This hook is called after the File has been successfully written to disk.
     * 
     * @param actionExecutionContext the execution context
     */
    protected fun afterWrite(actionExecutionContext: ActionExecutionContext?) {
    }

    public override fun getMnemonic(): String {
        return MNEMONIC
    }

    protected override fun getRawProgressMessage(): String {
        return ((if (makeExecutable()) "Writing script " else "Writing file ")
                + com.google.common.collect.Iterables.getOnlyElement<T?>(getOutputs()).prettyPrint())
    }

    /**
     * Whether the file write can be generated remotely. If the file is consumed in Blaze
     * unconditionally, it doesn't make sense to run remotely.
     */
    open fun isRemotable(): Boolean {
        return true
    }

    /**
     * This interface is used to get the contents of the file to output to aquery when using
     * --include_file_write_contents.
     */
    interface FileContentsProvider {
        @Throws(IOException::class)
        fun getFileContents(eventHandler: EventHandler?): String?

        fun makeExecutable(): Boolean
    }

    public override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?> {
        return com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    companion object {
        /** The default mnemonic for a file write action.  */
        const val MNEMONIC: String = "FileWrite"
    }
}
