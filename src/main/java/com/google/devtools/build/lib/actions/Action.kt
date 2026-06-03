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

import com.google.devtools.build.lib.actions.extra.ExtraActionInfo

/**
 * An Action represents a function from Artifacts to Artifacts executed as an atomic build step.
 * Examples include compilation of a single C++ source file, or linking a single library.
 * 
 * 
 * All subclasses of Action need to follow a strict set of invariants to ensure correctness on
 * incremental builds. In our experience, getting this wrong is a lot more expensive than any
 * benefits it might entail.
 * 
 * 
 * Use [com.google.devtools.build.lib.analysis.actions.SpawnAction] or [ ] where possible, and avoid writing
 * a new custom subclass.
 * 
 * 
 * These are the most important requirements for subclasses:
 * 
 * 
 *  * Actions must be generally immutable; we currently make an exception for C++, and that has
 * been a constant source of correctness issues; there are still ongoing incremental
 * correctness issues for C++ compilations, despite several rounds of fixes and even though
 * this is the oldest part of the code base.
 *  * Actions should be as lazy as possible - storing full lists of inputs or full command lines
 * in every action generally results in quadratic memory consumption. Use [       ] for inputs, and [       ] for command lines where
 * possible to share as much data between the different actions and their owning configured
 * targets.
 *  * However, actions must not reference configured targets or rule contexts themselves; only
 * reference the necessary underlying artifacts or strings, preferably as nested sets. Bazel
 * may attempt to garbage collect configured targets and rule contexts before execution to
 * keep memory consumption down, and referencing them prevents that.
 *  * In particular, avoid anonymous inner classes - when created in a non-static method, they
 * implicitly keep a reference to their enclosing class, even if that reference is unnecessary
 * for correct operation. Not doing so has caused significant increases in memory consumption
 * in the past.
 *  * Correct cache key computation in [.getKey] is critical for the correctness of
 * incremental builds; you may be tempted to intentionally exclude data from the cache key,
 * but be aware that every time we've done that, it later resulted in expensive debugging
 * sessions and bug fixes.
 *  * As much as possible, make the cache key computation obvious - fully hash every field
 * (except input contents, but including input and output names if they appear in the command
 * line) in the class, and avoid referencing anything that isn't needed for action execution,
 * such as [com.google.devtools.build.lib.analysis.config.BuildConfigurationValue]
 * objects or even fragments thereof; if the action has a command line, err on the side of
 * hashing the entire command line, even if that seems expensive. It's always safe to hash too
 * much - the negative effect on incremental build times is usually negligible.
 *  * Add test coverage for the cache key computation; use [       ] to generate as many combinations
 * of field values as possible; add test coverage every time you add another field.
 * 
 * 
 * 
 * These constraints are not easily enforced or tested for (e.g., ActionTester only checks that a
 * known set of fields is covered, not that all fields are covered), so carefully check all changes
 * to action subclasses.
 */
interface Action : ActionExecutionMetadata {
    /**
     * Prepares for executing this action; called by the Builder prior to executing the Action itself.
     * This method should prepare the file system, so that the execution of the Action can write the
     * output files. At a minimum any pre-existing and write protected output files should be removed
     * or the permissions should be changed, so that they can be safely overwritten by the action.
     * 
     * @throws IOException if there is an error deleting the outputs.
     * @throws InterruptedException if the execution is interrupted
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun prepare(
        execRoot: Path?,
        pathResolver: ArtifactPathResolver?,
        bulkDeleter: BulkDeleter?,
        cleanupArchivedArtifacts: Boolean
    )

    /**
     * Executes this action. This method *unconditionally does the work of the Action*, although
     * it may delegate some of that work to [ActionContext] instances obtained from the [ ], which may in turn perform caching at smaller granularity than an
     * entire action.
     * 
     * 
     * This method may not be invoked if an equivalent action (as determined by the hashes of the
     * input files, the list of output files, and the action cache key) has been previously executed,
     * possibly on another machine.
     * 
     * 
     * The framework guarantees that:
     * 
     * 
     *  * all declared inputs have already been successfully created,
     *  * the output directory for each file in `getOutputs()` has already been created,
     *  * this method is only called by at most one thread at a time, but subsequent calls may be
     * made from different threads,
     *  * for shared actions, at most one instance is executed per build.
     * 
     * 
     * 
     * Multiple instances of the same action implementation may be called in parallel.
     * Implementations must therefore be thread-compatible. Also see the class documentation for
     * additional invariants.
     * 
     * 
     * Implementations should attempt to detect interrupts, and exit quickly with an [ ].
     * 
     * @param actionExecutionContext services in the scope of the action, like the output and error
     * streams to use for messages arising during action execution
     * @return returns an ActionResult containing action execution metadata
     * @throws ActionExecutionException if execution fails for any reason
     * @throws InterruptedException if the execution is interrupted
     */
    @ConditionallyThreadCompatible
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult?

    /**
     * Returns true iff action must be executed regardless of its current state.
     * Default implementation can be overridden by some actions that might be
     * executed unconditionally under certain circumstances - e.g., if caching of
     * test results is not requested, this method could be used to force test
     * execution even if all dependencies are up-to-date.
     * 
     * 
     * Note, it is **very** important not to abuse this method, since it
     * completely overrides dependency checking. Any use of this method must
     * be carefully reviewed and proved to be necessary.
     * 
     * 
     * Note that the definition of [.isVolatile] depends on the
     * definition of this method, so be sure to consider both methods together
     * when making changes.
     */
    fun executeUnconditionally(): Boolean

    /**
     * Returns true if it's ever possible that [.executeUnconditionally]
     * could evaluate to true during the lifetime of this instance, false
     * otherwise.
     */
    fun isVolatile(): Boolean

    /**
     * Runs input discovery on this action.
     * 
     * 
     * May only be called if [.discoversInputs] returns true. Returns the set of input
     * artifacts that were not known at analysis time. May also call [.updateInputs]; if it
     * doesn't, the action itself must arrange for the newly discovered artifacts to be available
     * during action execution, probably by keeping state in the action instance and using a custom
     * action execution context and for [.updateInputs] to be called during the execution of the
     * action.
     * 
     * 
     * Since keeping state within an action is bad, don't do that unless there is a very good
     * reason to do so.
     * 
     * 
     * May return `null` if more dependencies were requested from skyframe but were
     * unavailable, meaning a restart is necessary.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun discoverInputs(actionExecutionContext: ActionExecutionContext?): NestedSet<Artifact?>?

    /**
     * Whether the action detected any of its inputs as unused on its most recent execution.
     * 
     * 
     * Only actions which [discover inputs][.discoversInputs] may prune inputs. The
     * action updates its inputs to the pruned set during [.execute]. [.discoversInputs]
     * should report all of the original inputs.
     */
    fun prunedInputs(): Boolean

    /** Prepare for input discovery, called before the first call to [.discoverInputs].  */
    fun prepareInputDiscovery() {}

    /**
     * Resets this action's inputs to a pre [input discovery][.discoverInputs] state.
     * 
     * 
     * This may be called on input-discovering actions during non-incremental builds, when it is
     * not worthwhile to retain the discovered inputs after the action completes execution. It may
     * still be necessary to rewind the action, so it must retain state necessary for re-execution.
     */
    fun resetDiscoveredInputs()

    /**
     * Returns the set of artifacts that can possibly be inputs. It will be called iff [ ][.inputsKnown] is false for the given action instance and there is a related cache entry in the
     * action cache.
     * 
     * 
     * Method must be redefined for any action for which [.inputsKnown] may return false.
     * 
     * 
     * The method is allowed to return source artifacts. They are useless, though, since exec paths
     * in the action cache referring to source artifacts are always resolved.
     */
    fun getAllowedDerivedInputs(): NestedSet<Artifact?>?

    /**
     * Called on [input-discovering][.discoversInputs] actions when the inputs of the action
     * become known, either during [.discoverInputs] or during [.execute].
     * 
     * 
     * When an action discovers inputs, this method must have been called by the time `#execute` returns.
     * 
     * 
     * In addition to being called from action implementations, it is also called by [ ] when an action is loaded from the on-disk action cache.
     */
    fun updateInputs(inputs: NestedSet<Artifact?>?)

    /**
     * Returns true if the output should bypass output filtering. This is used for test actions.
     */
    fun showsOutputUnconditionally(): Boolean

    /**
     * Called by [com.google.devtools.build.lib.analysis.extra.ExtraAction] at execution time to
     * extract information from this action into a protocol buffer to be used by extra_action rules.
     * 
     * 
     * As this method is called from the ExtraAction, make sure it is ok to call this method from a
     * different thread than the one this action is executed on.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder?

    /**
     * Called by [com.google.devtools.build.lib.analysis.actions.StarlarkAction] to use its
     * shadowed action, if any, complete list of environment variables in the Starlark action Spawn.
     * 
     * 
     * As this method is called from the StarlarkAction, make sure it is ok to call it from a
     * different thread than the one this action is executed on. By definition, the method should not
     * mutate any of the called action data but if necessary, its implementation must synchronize any
     * accesses to mutable data.
     */
    @Throws(CommandLineExpansionException::class)
    fun getEffectiveEnvironment(clientEnv: MutableMap<String?, String?>?): com.google.common.collect.ImmutableMap<String?, String?>?
}
