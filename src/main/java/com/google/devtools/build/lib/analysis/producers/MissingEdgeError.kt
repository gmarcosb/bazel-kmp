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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.configurationIdMessage

/**
 * A dependency error caused by a missing [Package] or [Target].
 * 
 * 
 * This class is structured this way to relay details of the error all the way out to the top
 * level which has both the base [TargetAndConfiguration] and the [ExtendedEventHandler]
 * references needed to construct the causes and events.
 */
class MissingEdgeError internal constructor(
    kind: DependencyKind,
    label: com.google.devtools.build.lib.cmdline.Label,
    cause: NoSuchThingException
) {
    private val kind: DependencyKind
    private val label: com.google.devtools.build.lib.cmdline.Label
    private val cause: NoSuchThingException

    init {
        this.kind = kind
        this.label = label
        this.cause = cause
    }

    /** Emits the causes and events associated with this error.  */
    fun emitCausesAndEvents(
        fromNode: TargetAndConfiguration,
        transitiveState: TransitiveDependencyState,
        listener: com.google.devtools.build.lib.events.ExtendedEventHandler
    ) {
        val from: com.google.devtools.build.lib.packages.Target = fromNode.getTarget()

        if (cause is RepositoryFetchException) {
            var repositoryLabel: com.google.devtools.build.lib.cmdline.Label?
            try {
                repositoryLabel =
                    com.google.devtools.build.lib.cmdline.Label.create(
                        LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER,
                        label.getRepository().getName()
                    )
            } catch (lse: LabelSyntaxException) {
                // We're taking the repository name from something that was already part of a label, so it
                // should be valid. If we really get into this strange we situation, better not try to be
                // smart and report the original label.
                repositoryLabel = label
            }
            transitiveState.addTransitiveCause(
                LoadingFailedCause(repositoryLabel, cause.getDetailedExitCode())
            )
            listener.handle(
                com.google.devtools.build.lib.events.Event.error(
                    TargetUtils.getLocationMaybe(from),
                    String.format(
                        "%s depends on %s in repository %s which failed to fetch. %s",
                        from.getLabel(), label, label.getRepository(), cause.message
                    )
                )
            )
            return
        }

        if (cause is NoSuchPackageException) {
            // Blames the rule for specifying an unavailable package.
            val configuration: BuildConfigurationValue? = fromNode.getConfiguration()
            listener.post(
                AnalysisRootCauseEvent.withConfigurationValue(configuration, label, cause.message)
            )
            transitiveState.addTransitiveCause(
                AnalysisFailedCause(
                    label, configurationIdMessage(configuration), cause.getDetailedExitCode()
                )
            )
        } else if (cause is NoSuchTargetException) {
            // If the child target was present, it already has an associated LoadingFailedCause.
            if (!(cause as NoSuchTargetException).hasTarget()) {
                transitiveState.addTransitiveCause(
                    LoadingFailedCause(label, cause.getDetailedExitCode())
                )
            }
        }

        val message: String?
        if (DependencyKind.isToolchain(kind)) {
            message = String.format(
                "Target '%s' depends on toolchain '%s', which cannot be found: %s'",
                from.getLabel(), label, cause.message
            )
        } else {
            message = TargetUtils.formatMissingEdge(from, label, cause, kind.getAttribute())
        }
        listener.handle(com.google.devtools.build.lib.events.Event.error(TargetUtils.getLocationMaybe(from), message))
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("kind", kind)
            .add("label", label)
            .add("cause", cause)
            .toString()
    }
}
