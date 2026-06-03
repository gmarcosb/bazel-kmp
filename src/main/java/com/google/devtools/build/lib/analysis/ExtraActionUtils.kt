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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A collection of static methods related to ExtraActions.
 */
internal object ExtraActionUtils {
    /**
     * Scans `action_listeners` associated with this build to see if any `extra_actions`
     * should be added to this configured target. If any action_listeners are present, a partial visit
     * of the artifact/action graph is performed (for as long as actions found are owned by this
     * [ConfiguredTarget]). Any actions that match the `action_listener` get an `extra_action` associated. The output artifacts of the extra_action are reported to the [ ] for bookkeeping.
     */
    @Throws(java.lang.InterruptedException::class)
    fun createExtraActionProvider(
        actionsWithoutExtraAction: MutableSet<ActionAnalysisMetadata?>, ruleContext: RuleContext
    ): ExtraActionArtifactsProvider {
        val configuration: BuildConfigurationValue? = ruleContext.getConfiguration()
        if (configuration.isToolConfiguration()) {
            return ExtraActionArtifactsProvider.EMPTY
        }

        var extraActionArtifacts: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> =
            com.google.common.collect.ImmutableList.of<Artifact.DerivedArtifact?>()
        val builder: NestedSetBuilder<Artifact.DerivedArtifact?> = NestedSetBuilder.stableOrder()

        val actionListenerLabels: MutableList<Label?> = configuration.getActionListeners()
        if (!actionListenerLabels.isEmpty()
            && ruleContext.attributes().getAttributeDefinition(":action_listener") != null
        ) {
            val visitor: ExtraActionsVisitor =
                ExtraActionsVisitor(ruleContext, computeMnemonicsToExtraActionMap(ruleContext))

            // The action list is modified within the body of the loop by the maybeAddExtraAction() call,
            // thus the copy
            for (action in com.google.common.collect.ImmutableList.copyOf<ActionAnalysisMetadata?>(
                ruleContext.getAnalysisEnvironment().getRegisteredActions()
            )) {
                if (!actionsWithoutExtraAction.contains(action)) {
                    visitor.maybeAddExtraAction(action)
                }
            }

            extraActionArtifacts = visitor.getAndResetExtraArtifacts()
            if (!extraActionArtifacts.isEmpty()) {
                builder.addAll(extraActionArtifacts)
            }
        }

        // Add extra action artifacts from dependencies
        for (provider in getProviders(
            ruleContext.getAllPrerequisites(), ExtraActionArtifactsProvider::class.java
        )) {
            builder.addTransitive(provider.getTransitiveExtraActionArtifacts())
        }

        return ExtraActionArtifactsProvider.create(
            NestedSetBuilder.DerivedArtifact > stableOrder<Artifact.DerivedArtifact?>()
                .addAll(extraActionArtifacts)
                .build(),
            builder.build()
        )
    }

    /**
     * Populates the configuration specific mnemonicToExtraActionMap
     * based on all action_listers selected by the user (via the blaze option
     * `--experimental_action_listener=<target>`).
     */
    private fun computeMnemonicsToExtraActionMap(
        ruleContext: RuleContext
    ): com.google.common.collect.Multimap<String?, ExtraActionSpec?> {
        // We copy the multimap here every time. This could be expensive.
        val mnemonicToExtraActionMap: com.google.common.collect.Multimap<String?, ExtraActionSpec?> =
            com.google.common.collect.HashMultimap.create<String?, ExtraActionSpec?>()
        for (actionListener in ruleContext.getPrerequisites(":action_listener")) {
            val provider: ExtraActionMapProvider? = actionListener.getProvider(ExtraActionMapProvider::class.java)
            if (provider == null) {
                ruleContext.ruleError(
                    java.lang.String.format(
                        "Unable to match experimental_action_listeners to this rule. "
                                + "Specified target %s is not an action_listener rule",
                        actionListener.getLabel().toString()
                    )
                )
            } else {
                mnemonicToExtraActionMap.putAll(provider.getExtraActionMap())
            }
        }
        return mnemonicToExtraActionMap
    }
}
