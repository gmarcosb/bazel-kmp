// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * A module that contains Starlark utilities for C++ support.
 * 
 * 
 * The Bazel team is planning to rewrite all native rules in Starlark. Many of these rules use
 * C++ functionality that is not presently exposed to the public Starlark C++ API. To speed up the
 * transition to Starlark, we are exposing functionality "as is" but preventing its use externally
 * until we are comfortable with the API which would need to be supported long term.
 * 
 * 
 * We are not opposed to gradually adding to and improving the public C++ API but nothing should
 * merged without following proper design processes and discussions.
 */
abstract class CcModule

    :
    CcModuleApi<StarlarkActionFactory?, Artifact?, FeatureConfigurationForStarlark?, CcToolchainVariables?, ConstraintValueInfo?, StarlarkRuleContext?> {
    abstract val ccToolchainProvider: Provider?

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getToolForAction(
        featureConfiguration: FeatureConfigurationForStarlark,
        actionName: String?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): String? {
        isCalledFromStarlarkCcCommon(thread)
        try {
            return featureConfiguration.getFeatureConfiguration().getToolPathForAction(actionName)
        } catch (illegalArgumentException: java.lang.IllegalArgumentException) {
            throw net.starlark.java.eval.EvalException(illegalArgumentException)
        }
    }

    // TODO(blaze-team): duplicate with the getExecutionRequirements below
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getToolRequirementForAction(
        featureConfiguration: FeatureConfigurationForStarlark,
        actionName: String?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): net.starlark.java.eval.Sequence<String?>? {
        isCalledFromStarlarkCcCommon(thread)
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(
            featureConfiguration.getFeatureConfiguration().getToolRequirementsForAction(actionName)
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getExecutionRequirements(
        featureConfiguration: FeatureConfigurationForStarlark,
        actionName: String?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): net.starlark.java.eval.Sequence<String?>? {
        isCalledFromStarlarkCcCommon(thread)
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(
            featureConfiguration.getFeatureConfiguration().getToolRequirementsForAction(actionName)
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun actionIsEnabled(
        featureConfiguration: FeatureConfigurationForStarlark,
        actionName: String?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): Boolean {
        isCalledFromStarlarkCcCommon(thread)
        return featureConfiguration.getFeatureConfiguration().actionIsConfigured(actionName)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getCommandLine(
        featureConfiguration: FeatureConfigurationForStarlark,
        actionName: String?,
        variables: CcToolchainVariables?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): net.starlark.java.eval.Sequence<String?>? {
        isCalledFromStarlarkCcCommon(thread)
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(
            featureConfiguration.getFeatureConfiguration().getCommandLine(actionName, variables)
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getEnvironmentVariable(
        featureConfiguration: FeatureConfigurationForStarlark,
        actionName: String?,
        variables: CcToolchainVariables?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): net.starlark.java.eval.Dict<String?, String?>? {
        isCalledFromStarlarkCcCommon(thread)
        return net.starlark.java.eval.Dict.immutableCopyOf<String?, String?>(
            featureConfiguration
                .getFeatureConfiguration()
                .getEnvironmentVariables(actionName, variables, PathMapper.NOOP)
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getVariables(thread: net.starlark.java.eval.StarlarkThread?): CcToolchainVariables? {
        isCalledFromStarlarkCcCommon(thread)
        return CcToolchainVariables.Companion.empty()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkExperimentalCcSharedLibrary(thread: net.starlark.java.eval.StarlarkThread): Boolean {
        isCalledFromStarlarkCcCommon(thread)
        return thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_CC_SHARED_LIBRARY)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIncompatibleDisableObjcLibraryTransition(thread: net.starlark.java.eval.StarlarkThread): Boolean {
        isCalledFromStarlarkCcCommon(thread)
        return thread
            .getSemantics()
            .getBool(BuildLanguageOptions.INCOMPATIBLE_DISABLE_OBJC_LIBRARY_TRANSITION)
    }

    override fun addGoExecGroupsToBinaryRules(thread: net.starlark.java.eval.StarlarkThread): Boolean {
        return thread.getSemantics().getBool(BuildLanguageOptions.ADD_GO_EXEC_GROUPS_TO_BINARY_RULES)
    }

    // TODO(b/65151735): Remove when cc_flags is entirely from features.
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun legacyCcFlagsMakeVariable(
        ccToolchainInfo: Info,
        thread: net.starlark.java.eval.StarlarkThread?
    ): String? {
        isCalledFromStarlarkCcCommon(thread)
        val ccToolchain: CcToolchainProvider = CcToolchainProvider.Companion.wrapOrThrowEvalException(ccToolchainInfo)
        return ccToolchain.getLegacyCcFlagsMakeVariable()
    }

    companion object {
        /**
         * Converts an object that can be the NoneType to the actual object if it is not or returns the
         * default value if none.
         * 
         * 
         * This operation is wildly unsound. It performs no dymamic checks (casts), it simply lies
         * about the type.
         */
        protected fun <T> convertFromNoneable(obj: Any?, defaultValue: T?): T? {
            if (net.starlark.java.eval.Starlark.UNBOUND === obj || net.starlark.java.eval.Starlark.isNullOrNone(obj)) {
                return defaultValue
            }
            return obj as T // totally unsafe
        }

        fun <T> nullIfNone(`object`: Any?, type: java.lang.Class<T?>): T? {
            return if (`object` !== net.starlark.java.eval.Starlark.NONE) type.cast(`object`) else null
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkPrivateStarlarkificationAllowlist(thread: net.starlark.java.eval.StarlarkThread?) {
            BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        }

        fun isStarlarkCcCommonCalledFromBuiltins(thread: net.starlark.java.eval.StarlarkThread): Boolean {
            val label: Label =
                (net.starlark.java.eval.Module.ofInnermostEnclosingStarlarkFunction(thread, 1)
                    .getClientData() as BazelModuleContext)
                    .label()
            return label.getPackageIdentifier().getRepository().name.equals("_builtins")
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        protected fun isCalledFromStarlarkCcCommon(thread: net.starlark.java.eval.StarlarkThread?) {
            val label: Label = BazelModuleContext.ofInnermostBzlOrThrow(thread).label()
            // Allow direct access to cc_common.bzl and to C++ linking code that can't use cc_common.bzl
            // directly without creating a cycle.
            if (!label.getCanonicalForm().endsWith("_builtins//:common/cc/cc_common.bzl") && !label.getCanonicalForm()
                    .endsWith("_builtins//:common/cc/cc_common_bazel.bzl") && !label.getCanonicalForm()
                    .contains("_builtins//:common/cc/compile") && !label.getCanonicalForm()
                    .contains("_builtins//:common/cc/link") && !label.getCanonicalForm()
                    .contains("_builtins//:common/cc/toolchain_config")
            ) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "cc_common_internal can only be used by cc_common.bzl in builtins, "
                            + "please use cc_common instead."
                )
            }
        }
    }
}
