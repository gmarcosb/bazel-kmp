// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util

import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.actions.AbstractAction
import java.util.concurrent.Callable

/**
 * A dummy action for testing.  Its execution runs the specified
 * Runnable or Callable, which is defined by the test case,
 * and touches all the output files.
 */
open class TestAction(effect: Callable<Void?>, inputs: NestedSet<Artifact?>, outputs: ImmutableSet<Artifact?>?) :
    AbstractAction(ActionsTestUtil.Companion.NULL_ACTION_OWNER, mandatoryArtifacts(inputs), outputs) {
    protected val effect: Callable<Void?>
    private val mandatoryInputs: NestedSet<Artifact?>?
    private val optionalInputs: ImmutableList<Artifact?>
    private var inputsDiscovered = false

    /** Use this constructor if the effect can't throw exceptions.  */
    constructor(
        effect: Runnable,
        inputs: NestedSet<Artifact?>,
        outputs: ImmutableSet<Artifact?>?
    ) : this(Executors.callable<Void?>(effect, null), inputs, outputs)

    /**
     * Use this constructor if the effect can throw exceptions. Any checked exception thrown will be
     * repackaged as an ActionExecutionException.
     */
    init {
        this.mandatoryInputs = getInputs()
        this.optionalInputs = optionalArtifacts(inputs)
        this.effect = effect
    }

    public override fun getMandatoryInputs(): NestedSet<Artifact?>? {
        return mandatoryInputs
    }

    public override fun discoversInputs(): Boolean {
        return !optionalInputs.isEmpty()
    }

    protected override fun inputsDiscovered(): Boolean {
        return inputsDiscovered
    }

    protected override fun setInputsDiscovered(inputsDiscovered: Boolean) {
        this.inputsDiscovered = inputsDiscovered
    }

    public override fun getOriginalInputs(): NestedSet<Artifact?>? {
        return mandatoryInputs
    }

    public override fun getAllowedDerivedInputs(): NestedSet<Artifact?> {
        return NestedSetBuilder.< Artifact > wrap < Artifact ? > (Order.STABLE_ORDER, optionalInputs)
    }

    public override fun discoverInputs(actionExecutionContext: ActionExecutionContext?): NestedSet<Artifact?>? {
        Preconditions.checkState(discoversInputs(), this)
        val discoveredInputs: NestedSet<Artifact?>? =
            NestedSetBuilder.wrap(
                Order.STABLE_ORDER, Iterables.filter<T?>(optionalInputs, Predicate { i: T? -> i.getPath().exists() })
            )
        updateInputs(
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(mandatoryInputs)
                .addTransitive(discoveredInputs)
                .build()
        )
        return discoveredInputs
    }

    @Throws(ActionExecutionException::class, InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
        for (artifact in getInputs().toList()) {
            // Do not check *.optional artifacts - artifacts with such extension are
            // used by tests to specify artifacts that may or may not be missing.
            // This is used, e.g., to test Blaze behavior when action has missing
            // input artifacts but still is successfully executed.
            check(artifact.getPath().exists()) {
                ("action's input file does not exist: "
                        + artifact.getPath())
            }
        }

        try {
            effect.call()
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Error) {
            throw e
        } catch (e: ActionExecutionException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            val code: DetailedExitCode? = CrashFailureDetails.detailedExitCodeForThrowable(e)
            throw ActionExecutionException(
                "TestAction failed due to exception: " + e.getMessage(), e, this, false, code
            )
        }

        try {
            for (artifact in getOutputs()) {
                FileSystemUtils.touchFile(artifact.getPath())
            }
        } catch (e: IOException) {
            throw AssertionError(e)
        }

        return ActionResult.EMPTY
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addPaths(Artifact.asSortedPathFragments(getOutputs()))
        fp.addPaths(Artifact.asSortedPathFragments(getMandatoryInputs().toList()))
    }

    public override fun getMnemonic(): String {
        return "Test"
    }

    /** No-op action that has exactly one output.  */
    @AutoCodec
    open class DummyAction @AutoCodec.Instantiator constructor(inputs: NestedSet<Artifact?>, primaryOutput: Artifact) :
        TestAction(
            NO_EFFECT, inputs, ImmutableSet.of<Artifact?>(primaryOutput)
        ) {
        constructor(input: Artifact?, output: Artifact?) : this(
            NestedSetBuilder.create(Order.STABLE_ORDER, input),
            output
        )
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val NO_EFFECT: Runnable = Runnable {}

        private fun isOptional(artifact: Artifact): Boolean {
            return artifact.getExecPath().getBaseName().endsWith(".optional")
        }

        private fun mandatoryArtifacts(inputs: NestedSet<Artifact?>): NestedSet<Artifact?> {
            return NestedSetBuilder.wrap(
                Order.STABLE_ORDER, Iterables.filter<T?>(inputs.toList(), Predicate { a: T? -> !isOptional(a) })
            )
        }

        private fun optionalArtifacts(inputs: NestedSet<Artifact?>): ImmutableList<Artifact?> {
            return ImmutableList.copyOf<E?>(Iterables.filter<T?>(inputs.toList(), Predicate { a: T? -> isOptional(a) }))
        }
    }
}
