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

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * Applies a configuration transition to a build options instance.
 * 
 * 
 * postwork - replay events/throw errors from transition implementation function and validate the
 * outputs of the transition. This only applies to Starlark transitions.
 */
internal class TransitionApplier
    (
    label: com.google.devtools.build.lib.cmdline.Label?,
    fromConfiguration: BuildConfigurationKey,
    transition: ConfigurationTransition,
    transitionCache: StarlarkTransitionCache,
    sink: ResultSink,
    eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?,
    runAfter: StateMachine?
) : StateMachine, ValueOrExceptionSink<TransitionException?> {
    internal interface ResultSink :
        com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyMapProducer.ResultSink {
        fun acceptTransitionError(e: TransitionException?)
    }

    // -------------------- Input --------------------
    private val label: com.google.devtools.build.lib.cmdline.Label?
    private val fromConfiguration: BuildConfigurationKey
    private val transition: ConfigurationTransition
    private val transitionCache: StarlarkTransitionCache

    // -------------------- Output --------------------
    private val sink: ResultSink
    private val eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?

    // -------------------- Sequencing --------------------
    private val runAfter: StateMachine?

    // -------------------- Internal State --------------------
    private var buildSettingsDetailsValue: StarlarkBuildSettingsDetailsValue? = null

    init {
        this.fromConfiguration = fromConfiguration
        this.transition = transition
        this.transitionCache = transitionCache
        this.sink = sink
        this.eventHandler = eventHandler
        this.runAfter = runAfter
        this.label = label
    }

    @Throws(java.lang.InterruptedException::class)
    override fun step(tasks: StateMachine.Tasks): StateMachine? {
        val doesStarlarkTransition: AtomicBoolean = AtomicBoolean(false)
        val stampDependent: AtomicBoolean = AtomicBoolean(false)
        try {
            transition.visit(
                StarlarkTransitionVisitor { t: StarlarkTransition? ->
                    doesStarlarkTransition.set(true)
                    if (!t.isExecTransition() && (t.readsStampSetting() || t.setsStampSetting())) {
                        stampDependent.set(true)
                    }
                } as StarlarkTransitionVisitor)
        } catch (e: TransitionException) {
            sink.acceptTransitionError(e)
            return runAfter
        }
        if (!doesStarlarkTransition.get()) {
            return BuildConfigurationKeyMapProducer(
                this.sink,
                this.runAfter,
                transition.apply(
                    TransitionUtil.restrict(transition, fromConfiguration.getOptions()), eventHandler
                ),
                this.label
            )
        }
        if (stampDependent.get()
            && fromConfiguration.getOptions().get(CoreOptions::class.java).getStampBinaries()
        ) {
            // Request the STAMP_SETTING_MARKER dep. It's a precomputed value so should already be done,
            // but return a reference to the next step anyway as a state machine best practice.
            tasks.lookUp(
                PrecomputedValue.STAMP_SETTING_MARKER.getKey(),
                java.util.function.Consumer { `val`: SkyValue? -> })
            return StateMachine { tasks: StateMachine.Tasks? -> this.handleStarlarkTransition(tasks) }
        }
        return handleStarlarkTransition(tasks)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun handleStarlarkTransition(tasks: StateMachine.Tasks): StateMachine? {
        val starlarkBuildSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            transitionCache.getAllStarlarkBuildSettings(
                transition,
                fromConfiguration.getOptions().get(CoreOptions::class.java).getCommandLineFlagAliasesMap()
            )
        val hostFlags: MutableSet<com.google.devtools.build.lib.cmdline.Label?> =
            HashSet<com.google.devtools.build.lib.cmdline.Label?>()

        // If the transition is the exec transition, we want to look up the host flag declared by
        // users in the blazerc/MODULE.bazel files with alias pointing to the starlark definition. This
        // is useful to determine exec propagation for flags with scope that starts with "exec:--".
        if (transition.getName().equals("exec")) {
            for (alias in fromConfiguration
                .getOptions()
                .get(CoreOptions::class.java)
                .getCommandLineFlagAliasesMap()
                .entrySet()) {
                if (alias.key.startsWith("host_")) {
                    hostFlags.add(alias.value)
                }
            }
        } else if (starlarkBuildSettings.isEmpty()) {
            // Quick escape if transition doesn't use any Starlark build settings.
            buildSettingsDetailsValue = StarlarkBuildSettingsDetailsValue.Companion.EMPTY
            return applyStarlarkTransition(tasks)
        }
        tasks.lookUp<TransitionException?>(
            StarlarkBuildSettingsDetailsValue.Companion.key(starlarkBuildSettings, hostFlags),
            TransitionException::class.java,
            this as ValueOrExceptionSink<TransitionException?>
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.applyStarlarkTransition(tasks) }
    }

    override fun acceptValueOrException(value: SkyValue?, e: TransitionException?) {
        if (value != null) {
            buildSettingsDetailsValue = value as StarlarkBuildSettingsDetailsValue
            return
        }
        if (e != null) {
            sink.acceptTransitionError(e)
            return
        }
        throw java.lang.IllegalArgumentException("No result received.")
    }

    @Throws(java.lang.InterruptedException::class)
    private fun applyStarlarkTransition(tasks: StateMachine.Tasks?): StateMachine? {
        if (buildSettingsDetailsValue == null) {
            return runAfter // There was an error.
        }

        val transitionedOptions: MutableMap<String?, BuildOptions?>
        try {
            transitionedOptions =
                transitionCache.computeIfAbsent(
                    fromConfiguration.getOptions(), transition, buildSettingsDetailsValue, eventHandler
                )
        } catch (e: TransitionException) {
            sink.acceptTransitionError(e)
            return runAfter
        } catch (e: java.lang.InterruptedException) {
            // Workaround for https://github.com/bazelbuild/bazel/issues/29132. Is there some way for
            // Skfyrame to handle this automaticaly without needing special checking here?
            return runAfter
        }

        return BuildConfigurationKeyMapProducer(
            this.sink, this.runAfter, transitionedOptions, this.label
        )
    }
}
