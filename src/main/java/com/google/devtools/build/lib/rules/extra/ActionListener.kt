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
package com.google.devtools.build.lib.rules.extra

import com.google.devtools.build.lib.actions.ActionConflictException

/**
 * Implementation for the 'action_listener' rule.
 */
class ActionListener : RuleConfiguredTargetFactory {
    @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        // This rule doesn't produce any output when listed as a build target.
        // Only when used via the --experimental_action_listener flag,
        // this rule instructs the build system to add additional outputs.

        val extraActions: MutableList<ExtraActionSpec?>?

        val extraActionMap: com.google.common.collect.Multimap<String?, ExtraActionSpec?>?

        val mnemonics: MutableSet<String?> =
            HashSet<Any?>(ruleContext.attributes().get("mnemonics", Types.STRING_LIST))
        extraActions = retrieveAndValidateExtraActions(ruleContext)
        val extraActionMapBuilder: ImmutableSortedKeyListMultimap.Builder<String?, ExtraActionSpec?> =
            ImmutableSortedKeyListMultimap.builder()
        for (mnemonic in mnemonics) {
            extraActionMapBuilder.putAll(mnemonic, extraActions)
        }
        extraActionMap = extraActionMapBuilder.build()
        return RuleConfiguredTargetBuilder(ruleContext)
            .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
            .add(ExtraActionMapProvider::class.java, ExtraActionMapProvider(extraActionMap))
            .build()
    }

    /**
     * Loads the targets listed in the 'extra_actions' attribute of this rule.
     * Validates these targets to be extra_actions indeed. And checks if the
     * blaze version number is in the range of the blaze_version restrictions on the rule.
     */
    private fun retrieveAndValidateExtraActions(ruleContext: RuleContext): MutableList<ExtraActionSpec?> {
        val extraActions: MutableList<ExtraActionSpec?> = java.util.ArrayList<ExtraActionSpec?>()
        for (prerequisite in ruleContext.getPrerequisites("extra_actions")) {
            val spec: ExtraActionSpec? = prerequisite.getProvider(ExtraActionSpec::class.java)
            if (spec == null) {
                ruleContext.attributeError(
                    "extra_actions", java.lang.String.format(
                        "target %s is not an "
                                + "extra_action rule", prerequisite.label.toString()
                    )
                )
            } else {
                extraActions.add(spec)
            }
        }
        if (extraActions.isEmpty()) {
            ruleContext.attributeWarning(
                "extra_actions", "No extra_action is specified for this version of bazel."
            )
        }
        return extraActions
    }
}
