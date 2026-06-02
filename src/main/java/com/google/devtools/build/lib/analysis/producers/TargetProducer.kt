// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * Looks up a [Target] from its [Package] using its [Label].
 * 
 * 
 * This is used by the [TargetAndConfigurationProducer]. In contrast to the target lookup
 * in [ConfiguredTargetAndDataProducer], this one does not assume that target presence has
 * already been verified.
 */
internal class TargetProducer(
    label: com.google.devtools.build.lib.cmdline.Label,
    transitiveState: TransitiveDependencyState,
    sink: ResultSink,
    runAfter: StateMachine?
) : StateMachine, ValueOrExceptionSink<NoSuchPackageException?> {
    internal interface ResultSink {
        fun acceptTarget(target: com.google.devtools.build.lib.packages.Target?)

        fun acceptTargetError(error: NoSuchPackageException?)

        fun acceptTargetError(error: NoSuchTargetException?, location: net.starlark.java.syntax.Location?)
    }

    // -------------------- Input --------------------
    private val label: com.google.devtools.build.lib.cmdline.Label
    private val transitiveState: TransitiveDependencyState

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Sequencing --------------------
    private val runAfter: StateMachine?

    // -------------------- Internal State --------------------
    private var pkg: com.google.devtools.build.lib.packages.Package? = null

    init {
        this.label = label
        this.transitiveState = transitiveState
        this.sink = sink
        this.runAfter = runAfter
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        tasks.lookUp<NoSuchPackageException?>(
            label.getPackageIdentifier(),
            NoSuchPackageException::class.java,
            this as ValueOrExceptionSink<NoSuchPackageException?>
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.unwrapTarget(tasks) }
    }

    override fun acceptValueOrException(
        value: SkyValue?, error: NoSuchPackageException?
    ) {
        if (value != null) {
            this.pkg = (value as PackageValue).getPackage()
            return
        }

        sink.acceptTargetError(error)
    }

    private fun unwrapTarget(tasks: StateMachine.Tasks?): StateMachine? {
        if (pkg == null) {
            return StateMachine.DONE // An error occurred.
        }

        val target: com.google.devtools.build.lib.packages.Target
        try {
            target = pkg.getTarget(label.getName())
        } catch (e: NoSuchTargetException) {
            transitiveState.addTransitiveCause(LoadingFailedCause(label, e.getDetailedExitCode()))
            sink.acceptTargetError(e, pkg.getBuildFile().getLocation())
            return runAfter
        }

        if (pkg.containsErrors()) {
            val failureDetail: FailureDetail? =
                com.google.devtools.build.lib.packages.Package.contextualizeFailureDetailForTarget(
                    pkg.getFailureDetail(),
                    target
                )
            // The target can be loaded but may have associated errors, for example, a missing required
            // attribute. In these cases, instead of failing fast, it's possible to perform dependency
            // resolution using the target-in-error to uncover any other errors that could be present in
            // its dependencies. This error is turned into an exception when the transitive causes are
            // examined after dependency resolution.
            transitiveState.addTransitiveCause(
                LoadingFailedCause(label, DetailedExitCode.of(failureDetail))
            )
        }
        transitiveState.updateTransitivePackages(pkg.getMetadata())
        sink.acceptTarget(target)
        return runAfter
    }
}
