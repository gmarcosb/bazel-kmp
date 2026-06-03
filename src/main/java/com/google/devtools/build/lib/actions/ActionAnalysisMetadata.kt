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
 * An Analysis phase interface for an [Action] or Action-like object, containing only
 * side-effect-free query methods for information needed during action analysis.
 */
interface ActionAnalysisMetadata {
    /**
     * Returns the owner of this executable if this executable can supply verbose information. This is
     * typically the rule that constructed it; see ActionOwner class comment for details.
     */
    fun getOwner(): ActionOwner?

    /**
     * Returns true if the action can be shared, i.e. multiple configured targets can create the same
     * action.
     * 
     * 
     * In theory, these should not exist, but in practice, they do.
     */
    fun isShareable(): Boolean

    /**
     * Returns a mnemonic (string constant) for this kind of action; written into the master log so
     * that the appropriate parser can be invoked for the output of the action. Effectively a public
     * method as the value is used by the extra_action feature to match actions.
     */
    fun getMnemonic(): String?

    /**
     * Returns a string encoding all of the significant behaviour of this Action that might affect the
     * output. The general contract of `getKey` is this: if the work to be performed by the
     * execution of this action changes, the key must change.
     * 
     * 
     * As a corollary, the build system is free to omit the execution of an Action `a1`
     * if (a) at some time in the past, it has already executed an Action `a0` with the
     * same key as `a1`, (b) the names and contents of the input files listed by `
     * a1.getInputs()` are identical to the names and contents of the files listed by `
     * a0.getInputs()`, and (c) the names and values in the client environment of the variables
     * listed by `a1.getClientEnvironmentVariables()` are identical to those listed by
     * `a0.getClientEnvironmentVariables()`.
     * 
     * 
     * Examples of changes that should affect the key are:
     * 
     * 
     *  * Changes to the BUILD file that materially affect the rule which gave rise to this Action.
     *  * Changes to the command-line options, environment, or other global configuration resources
     * which affect the behaviour of this kind of Action (other than changes to the names of the
     * input/output files, which are handled externally).
     *  * An upgrade to the build tools which changes the program logic of this kind of Action
     * (typically this is achieved by incorporating a UUID into the key, which is changed each
     * time the program logic of this action changes).
     * 
     * 
     * 
     * Note the following exception: for actions that discover inputs, the key must change if any
     * input names change or else action validation may falsely validate.
     * 
     * 
     * In case the [InputMetadataProvider] is not provided, the key is not guaranteed to be
     * correct. In fact, getting the key of an action is generally impossible until we have all the
     * information necessary to execute the action. An example of this is when arguments to an action
     * are defined as a lazy evaluation of Starlark over outputs of another action, after expanding
     * directories. In such case, if the dependent action outputs a tree artifact, creating a truly
     * unique key will depend on knowing the tree artifact contents. At analysis time, we only know
     * about the tree artifact directory and we find what is in it only after we execute that action.
     */
    @Throws(java.lang.InterruptedException::class)
    fun getKey(
        actionKeyContext: ActionKeyContext?, inputMetadataProvider: InputMetadataProvider?
    ): String?

    /**
     * Returns a pretty string representation of this action, suitable for use in progress messages or
     * error messages.
     */
    fun prettyPrint(): String?

    /** Returns a description of this action.  */
    fun describe(): String?

    /**
     * Returns the (possibly empty) set of tool artifacts that this action depends upon.
     * 
     * 
     * Tools are a subset of [.getInputs] and used by the workers to determine whether a
     * compiler has changed since the last time it was used. This should include all artifacts that
     * the tool does not dynamically reload / check on each unit of work - e.g. its own binary, the
     * JDK for Java binaries, shared libraries, ... but not a configuration file, if it reloads that
     * when it has changed.
     * 
     * 
     * If this method does not return exactly the right set of artifacts, the following can happen:
     * If an artifact that should be included is missing, the tool might not be restarted when it
     * should, and builds can become incorrect (example: The compiler binary is not part of this set,
     * then the compiler gets upgraded, but the worker strategy still reuses the old version). If an
     * artifact that should *not* be included is accidentally part of this set, the worker
     * process will be restarted more often that is necessary - e.g. if a file that is unique to each
     * unit of work, e.g. the source code that a compiler should compile for a compile action, is part
     * of this set, then the worker will never be reused and will be restarted for each unit of work.
     */
    fun getTools(): NestedSet<Artifact?>?

    /**
     * Returns the input Artifacts that this Action depends upon. May be empty.
     * 
     * 
     * For actions that do input discovery, a different result may be returned before and after
     * action execution, because input discovery may add or remove inputs. The original input set may
     * be retrieved from [ActionExecutionMetadata.getOriginalInputs].
     */
    fun getInputs(): NestedSet<Artifact?>?

    /**
     * Returns this action's original inputs prior to input discovery.
     * 
     * 
     * Unlike [.getInputs], the same result is returned before and after action execution.
     */
    fun getOriginalInputs(): NestedSet<Artifact?>?

    /**
     * Returns the input Artifacts that must be built before the action can be executed, but are not
     * dependencies of the action in the action cache.
     * 
     * 
     * Useful for actions that do input discovery: then these Artifacts will be readable during
     * input discovery and then it can be decided which ones are actually necessary.
     */
    fun getSchedulingDependencies(): NestedSet<Artifact?>?

    /**
     * Returns the environment variables from the client environment that this action depends on. May
     * be empty.
     * 
     * 
     * Warning: For optimization reasons, the available environment variables are restricted to
     * those white-listed on the command line. If actions want to specify additional client
     * environment variables to depend on, that restriction must be lifted in [ ].
     */
    fun getClientEnvironmentVariables(): MutableCollection<String?>?

    /**
     * Returns the output artifacts that this action generates.
     * 
     * 
     * The returned [Collection] is immutable, non-empty, and duplicate-free.
     */
    fun getOutputs(): MutableCollection<Artifact?>?

    /**
     * Returns input files that need to be present to allow extra_action rules to shadow this action
     * correctly when run remotely. This is at least the normal inputs of the action, but may include
     * other files as well. For example C(++) compilation may perform include file header scanning.
     * This needs to be mirrored by the extra_action rule. Called by [ ] at execution time for actions that
     * return true for {link ActionExecutionMetadata#discoversInputs}.
     * 
     * @param actionExecutionContext Services in the scope of the action, like the Out/Err streams.
     * @throws ActionExecutionException only when code called from this method throws that exception.
     * @throws InterruptedException if interrupted
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun getInputFilesForExtraAction(actionExecutionContext: ActionExecutionContext?): NestedSet<Artifact?>?

    /**
     * Returns the set of output Artifacts that are required to be saved. This is used to identify
     * items that would otherwise be potentially identified as orphaned (not consumed by any
     * downstream [Action]s and potentially discarded during the build process.
     * 
     * 
     * Do not call unless you are in the business of identifying orphaned artifacts: otherwise just
     * use [.getOutputs].
     */
    fun getMandatoryOutputs(): com.google.common.collect.ImmutableSet<Artifact?>?

    /**
     * Returns the "primary" input of this action, or `null` if this action has no inputs.
     * 
     * 
     * For example, a C++ compile action would return the .cc file which is being compiled,
     * irrespective of the other inputs.
     */
    fun getPrimaryInput(): Artifact?

    /**
     * Returns the "primary" output of this action, which is the same as the first artifact in [ ][.getOutputs].
     * 
     * 
     * For example, the linked library would be the primary output of a LinkAction.
     * 
     * 
     * Never returns null.
     */
    fun getPrimaryOutput(): Artifact?

    /**
     * Returns an iterable of input Artifacts that MUST exist prior to executing an action. In other
     * words, in case when action is scheduled for execution, builder will ensure that all artifacts
     * returned by this method are present in the filesystem (artifact.getPath().exists() is true) or
     * action execution will be aborted with an error that input file does not exist. While in
     * majority of cases this method will return all action inputs, for some actions (e.g.
     * CppCompileAction) it can return a subset of inputs because that not all action inputs might be
     * mandatory for action execution to succeed (e.g. header files retrieved from *.d file from the
     * previous build).
     */
    fun getMandatoryInputs(): NestedSet<Artifact?>?

    /**
     * Returns a String to String map containing the execution properties of this action.
     * 
     * 
     * These properties are typically inherited from [.getOwner] and contain the
     * exec_properties provided on the target or execution platform level. Subclasses can override
     * this to return an empty map if that is more appropriate.
     */
    fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>?

    /**
     * Returns the [PlatformInfo] platform this action should be executed on. If the execution
     * platform is `null`, then the host platform is assumed.
     */
    fun getExecutionPlatform(): PlatformInfo?

    /**
     * Returns the execution requirements for this action, or an empty map if the action type does not
     * have access to execution requirements.
     */
    fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return getExecProperties()
    }

    companion object {
        fun mergeMaps(
            first: com.google.common.collect.ImmutableMap<String?, String?>,
            second: com.google.common.collect.ImmutableMap<String?, String?>
        ): com.google.common.collect.ImmutableMap<String?, String?>? {
            if (first.isEmpty()) {
                return second
            }
            if (second.isEmpty()) {
                return first
            }
            return com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, String?>(first.size + second.size)
                .putAll(first)
                .putAll(second)
                .buildKeepingLast()
        }

        /**
         * Return this key from [.getKey] to signify a failed key computation.
         * 
         * 
         * Actions that return this value should fail to execute.
         * 
         * 
         * Consumers must either gracefully handle multiple failed actions having the same key,
         * (recommended), or check against this value explicitly.
         */
        const val KEY_ERROR: String = "1ea50e01-0349-4552-80cf-76cf520e8592"
    }
}
