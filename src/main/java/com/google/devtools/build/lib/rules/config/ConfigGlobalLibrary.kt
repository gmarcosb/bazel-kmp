// Copyright 2018 The Bazel Authors. All rights reserved.
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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.cmdline.LabelConstants.COMMAND_LINE_OPTION_PREFIX

/**
 * Implementation of [ConfigGlobalLibraryApi].
 * 
 * 
 * A collection of top-level Starlark functions pertaining to configuration.
 */
class ConfigGlobalLibrary : ConfigGlobalLibraryApi {
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun transition(
        implementation: net.starlark.java.eval.StarlarkCallable?,
        inputs: net.starlark.java.eval.Sequence<*>?,  // <String> expected
        outputs: net.starlark.java.eval.Sequence<*>?,  // <String> expected
        thread: net.starlark.java.eval.StarlarkThread
    ): ConfigurationTransitionApi {
        val semantics: net.starlark.java.eval.StarlarkSemantics = thread.getSemantics()
        val moduleContext: BazelModuleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread)

        val inputsList: MutableList<String> =
            net.starlark.java.eval.Sequence.cast<String?>(inputs, String::class.java, "inputs")
        val outputsList: MutableList<String> =
            net.starlark.java.eval.Sequence.cast<String?>(outputs, String::class.java, "outputs")
        validateBuildSettingKeys(
            inputsList,
            Settings.INPUTS,  /* allowIncompatibleAndExperimentalOptions= */
            false,
            moduleContext.packageContext()
        )
        validateBuildSettingKeys(
            outputsList,
            Settings.OUTPUTS,  /* allowIncompatibleAndExperimentalOptions= */
            false,
            moduleContext.packageContext()
        )
        val location: net.starlark.java.syntax.Location? = thread.getCallerLocation()
        return StarlarkDefinedConfigTransition.newRegularTransition(
            implementation,
            inputsList,
            outputsList,
            semantics,
            moduleContext.label(),
            location,
            moduleContext.repoMapping(),
            thread.getSemantics().get<T?>(BuildLanguageOptions.INCOMPATIBLE_DISABLE_TRANSITIONS_OPTIONS)
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun execTransition(
        implementation: net.starlark.java.eval.StarlarkCallable?,
        inputs: net.starlark.java.eval.Sequence<*>?,  // <String> expected
        outputs: net.starlark.java.eval.Sequence<*>?,  // <String> expected
        thread: net.starlark.java.eval.StarlarkThread
    ): ConfigurationTransitionApi {
        // TODO: blaze-configurability-team - When we relax this, add checks that regular usages of
        // transitions (ie, rule and attribute) cannot use exec_transition.
        BuiltinRestriction.failIfCalledOutsideBuiltins(thread)

        val semantics: net.starlark.java.eval.StarlarkSemantics = thread.getSemantics()
        val moduleContext: BazelModuleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread)

        val inputsList: MutableList<String> =
            net.starlark.java.eval.Sequence.cast<String?>(inputs, String::class.java, "inputs")
        val outputsList: MutableList<String> =
            net.starlark.java.eval.Sequence.cast<String?>(outputs, String::class.java, "outputs")
        validateBuildSettingKeys(
            inputsList,
            Settings.INPUTS,  /* allowIncompatibleAndExperimentalOptions= */
            true,
            moduleContext.packageContext()
        )
        validateBuildSettingKeys(
            outputsList,
            Settings.OUTPUTS,  /* allowIncompatibleAndExperimentalOptions= */
            true,
            moduleContext.packageContext()
        )
        val location: net.starlark.java.syntax.Location? = thread.getCallerLocation()
        return StarlarkDefinedConfigTransition.newExecTransition(
            implementation,
            inputsList,
            outputsList,
            semantics,
            moduleContext.label(),
            location,
            moduleContext.repoMapping(),
            thread.getSemantics().get<T?>(BuildLanguageOptions.INCOMPATIBLE_DISABLE_TRANSITIONS_OPTIONS)
        )
    }

    // TODO(b/237422931): move into testing module
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun analysisTestTransition(
        changedSettings: net.starlark.java.eval.Dict<*, *>?,  // <String, String> expected
        thread: net.starlark.java.eval.StarlarkThread
    ): ConfigurationTransitionApi {
        val moduleContext: BazelModuleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread)
        val changedSettingsMap: MutableMap<String?, Any?> =
            net.starlark.java.eval.Dict.cast<String?, Any?>(
                changedSettings,
                String::class.java,
                Any::class.java,
                "changed_settings dict"
            )
        validateBuildSettingKeys(
            changedSettingsMap.keys,
            Settings.OUTPUTS,  /* allowIncompatibleAndExperimentalOptions= */
            true,
            moduleContext.packageContext()
        )
        val location: net.starlark.java.syntax.Location? = thread.getCallerLocation()
        return StarlarkDefinedConfigTransition.newAnalysisTestTransition(
            changedSettingsMap,
            moduleContext.repoMapping(),
            moduleContext.label(),
            location,
            thread.getSemantics().get<T?>(BuildLanguageOptions.INCOMPATIBLE_DISABLE_TRANSITIONS_OPTIONS)
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun validateBuildSettingKeys(
        optionKeys: Iterable<String>,
        keyErrorDescriptor: Settings?,
        allowIncompatibleAndExperimentalOptions: Boolean,
        packageContext: Label.PackageContext?
    ) {
        val processedOptions: HashSet<String?> = HashSet<String?>()
        val singularErrorDescriptor = if (keyErrorDescriptor === Settings.INPUTS) "input" else "output"

        for (optionKey in optionKeys) {
            if (!optionKey.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
                try {
                    val label: Label = Label.parseWithRepoContext(optionKey, packageContext)
                    if (!label.getRepository().isVisible()) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "invalid transition %s '%s': no repo visible as @%s from %s",
                            singularErrorDescriptor,
                            label,
                            label.getRepository().name,
                            label.getRepository().getContextRepoDisplayString()
                        )
                    }
                } catch (e: LabelSyntaxException) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "invalid transition %s '%s'. If this is intended as a native option, "
                                + "it must begin with //command_line_option: %s",
                        singularErrorDescriptor, optionKey, e.getMessage()
                    )
                }
            } else {
                val optionName: String = optionKey.substring(COMMAND_LINE_OPTION_PREFIX.length())
                if (!allowIncompatibleAndExperimentalOptions && !validOptionName(optionName)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Invalid transition %s '%s'. Cannot transition on --experimental_* or "
                                + "--incompatible_* options",
                        singularErrorDescriptor, optionKey
                    )
                }
            }
            if (!processedOptions.add(optionKey)) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "duplicate transition %s '%s'",
                    singularErrorDescriptor,
                    optionKey
                )
            }
        }
    }

    companion object {
        /**
         * Flags that user-defined transitions aren't allowed to set.
         * 
         * 
         * Exec transitions are exempt from this because they already set many non-standard flags.
         * Maybe that can change in a future migration, but that's their current semantics. See caller
         * code for implementation details.
         */
        private fun validOptionName(optionName: String): Boolean {
            if (optionName.startsWith("experimental_")) {
                // Don't allow experimental flags.
                return false
            }

            if (optionName == "incompatible_enable_cc_toolchain_resolution"
                || optionName == "incompatible_enable_apple_toolchain_resolution"
            ) {
                // This is specifically allowed.
                return true
            } else if (optionName.startsWith("incompatible_")) {
                // Don't allow other incompatible flags.
                return false
            }

            return true
        }
    }
}
