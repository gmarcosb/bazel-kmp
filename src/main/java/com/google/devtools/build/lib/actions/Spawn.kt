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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/**
 * An object representing a subprocess to be invoked, including its command and arguments, its
 * working directory, its environment, a boolean indicating whether remote execution is appropriate
 * for this command, and if so, the set of files it is expected to read and write.
 */
interface Spawn : DescribableExecutionUnit {
    /**
     * Out-of-band data for this spawn. This can be used to signal hints (hardware requirements, local
     * vs. remote) to the execution subsystem. This data can come from multiple places e.g. tags, hard
     * coded into rule logic, etc.
     * 
     * 
     * The data in this field can be in one of two forms and it is up to the caller of this method
     * to extract the data it cares about. Forms:
     * 
     * 
     *  * true key-value pairs
     *  * string tags from [*]
     * which can be added to the map as keys with arbitrary values (canonically the empty
     * string)
     * 
     * 
     * 
     * Callers of this method may also be interested in the [.getCombinedExecProperties].
     * See its javadoc for a comparison.
     */
    fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?>?

    /** Returns the command (the first element) and its arguments.  */
    public override fun getArguments(): com.google.common.collect.ImmutableList<String?>?

    /**
     * Returns the initial environment of the process. If null, the environment is inherited from the
     * parent process.
     */
    public override fun getEnvironment(): com.google.common.collect.ImmutableMap<String?, String?>?

    /**
     * Returns the list of files that are required to execute this spawn (e.g. the compiler binary),
     * in contrast to files necessary for the tool to do its work (e.g. source code to be compiled).
     * 
     * 
     * The returned set of files is a subset of what getInputFiles() returns.
     * 
     * 
     * This method explicitly does not expand runfiles trees. Pass the result to an appropriate
     * utility method on [com.google.devtools.build.lib.actions.Artifact] to expand them.
     * 
     * 
     * This is for use with persistent workers, so we can restart workers when their binaries have
     * changed.
     */
    fun getToolFiles(): NestedSet<out ActionInput?>?

    /**
     * Returns the list of files that this command may read.
     * 
     * 
     * This method explicitly does not expand runfiles trees. Pass the result to an appropriate
     * utility method on [com.google.devtools.build.lib.actions.Artifact] to expand them.
     * 
     * 
     * This is for use with remote execution, so we can ship inputs before starting the command.
     * Order stability across multiple calls should be upheld for performance reasons.
     */
    fun getInputFiles(): NestedSet<out ActionInput?>?

    /**
     * Returns the collection of files that this command will write. Callers should not mutate the
     * result.
     * 
     * 
     * This is for use with remote execution, so remote execution does not have to guess what
     * outputs the process writes. While the order does not affect the semantics, it should be stable
     * so it can be cached.
     */
    fun getOutputFiles(): MutableCollection<out ActionInput?>?

    /**
     * Returns the output files that should be considered to be "generated" by this spawn for purposes
     * of reconstructing the execution graph in [ ].
     * 
     * 
     * This method is only used for constructing a model of the execution graph and does not affect
     * running this spawn in any way. It can be overridden to provide a clearer representation of the
     * execution graph in the face of oddities such as a difference between [.getOutputFiles]
     * and [Action.getOutputs].
     */
    fun getOutputEdgesForExecutionGraph(): Iterable<out ActionInput?>? {
        return getOutputFiles()
    }

    /**
     * Returns true if `output` must be created for the action to succeed. Can be used by remote
     * execution implementations to mark a command as failed if it did not create an output, even if
     * the command itself exited with a successful exit code.
     * 
     * 
     * Some actions, like tests, may have optional files (like .xml files) that may be created, but
     * are not required, so their spawns should return false for those optional files. Note that in
     * general, every output in [ActionAnalysisMetadata.getOutputs] is checked for existence in
     * [com.google.devtools.build.lib.skyframe.SkyframeActionExecutor.checkOutputs], so
     * eventually all those outputs must be produced by at least one `Spawn` for that action, or
     * locally by the action in some cases.
     * 
     * 
     * This method should not be overridden by any new Spawns if possible: outputs should be
     * mandatory.
     */
    fun isMandatoryOutput(output: ActionInput?): Boolean {
        return true
    }

    /** Returns the resource owner for local fallback.  */
    fun getResourceOwner(): ActionExecutionMetadata?

    /**
     * Returns the amount of resources needed for local execution. Calling this may trigger an
     * expensive computation: do not call unless actually needed!
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    fun getLocalResources(): ResourceSet?

    /** Returns a mnemonic (string constant) for this kind of spawn.  */
    public override fun getMnemonic(): String?

    /**
     * Returns execution properties related to this spawn.
     * 
     * 
     * Note that this includes data from the execution platform's exec_properties as well as
     * target-level exec_properties.
     * 
     * 
     * Callers might also be interested in [.getExecutionInfo] above. [ ][.getExecutionInfo] can be set by multiple sources while this data is set via the `exec_properties` attribute on targets and platforms.
     */
    fun getCombinedExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return getResourceOwner().getOwner().getExecProperties()
    }

    fun getExecutionPlatform(): PlatformInfo?

    public override fun getExecutionPlatformLabel(): Label? {
        val executionPlatform: PlatformInfo? = getExecutionPlatform()
        return if (executionPlatform != null) executionPlatform.label() else null
    }

    public override fun getConfigurationChecksum(): String? {
        return getResourceOwner().getOwner().getConfigurationChecksum()
    }

    public override fun getTargetDescription(): String? {
        return getResourceOwner().getOwner().getDescription()
    }

    fun getTargetLabel(): Label? {
        return getResourceOwner().getOwner().getLabel()
    }

    /**
     * Returns the [PathMapper] that was used to create this spawn and that should be used to
     * map the paths of the spawn's inputs and outputs.
     */
    fun getPathMapper(): PathMapper? {
        return PathMapper.Companion.NOOP
    }
}
