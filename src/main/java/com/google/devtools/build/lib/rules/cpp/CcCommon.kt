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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.analysis.config.CompilationMode

/** Common parts of the implementation of cc rules.  */
object CcCommon {
    private val PIC_CONFIGURATION_ERROR = ("PIC compilation is requested but the toolchain does not support it "
            + "(feature named 'supports_pic' is not enabled)")

    private val ALL_COMPILE_ACTIONS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(
            CppActionNames.C_COMPILE,
            CppActionNames.CPP_COMPILE,
            CppActionNames.CPP_HEADER_PARSING,
            CppActionNames.CPP_MODULE_COMPILE,
            CppActionNames.CPP_MODULE_CODEGEN,
            CppActionNames.CPP_MODULE_DEPS_SCANNING,
            CppActionNames.CPP20_MODULE_COMPILE,
            CppActionNames.CPP20_MODULE_CODEGEN,
            CppActionNames.ASSEMBLE,
            CppActionNames.PREPROCESS_ASSEMBLE,
            CppActionNames.CLIF_MATCH,
            CppActionNames.LINKSTAMP_COMPILE,
            CppActionNames.CC_FLAGS_MAKE_VARIABLE,
            CppActionNames.LTO_BACKEND,
            CppActionNames.CPP_HEADER_ANALYSIS
        )

    private val ALL_LINK_ACTIONS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(
            CppActionNames.LTO_INDEX_EXECUTABLE,
            CppActionNames.LTO_INDEX_DYNAMIC_LIBRARY,
            CppActionNames.LTO_INDEX_NODEPS_DYNAMIC_LIBRARY,
            LinkTargetType.EXECUTABLE.getActionName(),
            LinkTargetType.DYNAMIC_LIBRARY.getActionName(),
            LinkTargetType.NODEPS_DYNAMIC_LIBRARY.getActionName()
        )

    private val ALL_ARCHIVE_ACTIONS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(LinkTargetType.STATIC_LIBRARY.getActionName())

    private val ALL_OTHER_ACTIONS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(CppActionNames.STRIP)

    /** Action configs we request to enable.  */
    private val DEFAULT_ACTION_CONFIGS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.builder<String?>()
            .addAll(ALL_COMPILE_ACTIONS)
            .addAll(ALL_LINK_ACTIONS)
            .addAll(ALL_ARCHIVE_ACTIONS)
            .addAll(ALL_OTHER_ACTIONS)
            .build()

    private val OBJC_ACTIONS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(
            CppActionNames.OBJC_COMPILE,
            CppActionNames.OBJCPP_COMPILE,
            CppActionNames.OBJC_FULLY_LINK,
            CppActionNames.OBJC_EXECUTABLE
        )

    private fun getCoverageFeatures(cppConfiguration: CppConfiguration): com.google.common.collect.ImmutableList<String?> {
        val coverageFeatures: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        if (cppConfiguration.collectCodeCoverage()) {
            coverageFeatures.add(CppRuleClasses.COVERAGE)
            if (cppConfiguration.useLLVMCoverageMapFormat()) {
                coverageFeatures.add(CppRuleClasses.LLVM_COVERAGE_MAP_FORMAT)
            } else {
                coverageFeatures.add(CppRuleClasses.GCC_COVERAGE_MAP_FORMAT)
            }
        }
        return coverageFeatures.build()
    }

    /**
     * Legacy implementation of configure_features only used in tests.
     * 
     */
    @Deprecated("The uses should be replaced with <code>cc_common.configure_features</code>.")
    @Throws(net.starlark.java.eval.EvalException::class)
    fun configureFeaturesOrThrowEvalException(
        requestedFeatures: com.google.common.collect.ImmutableSet<String?>,
        unsupportedFeatures: com.google.common.collect.ImmutableSet<String?>,
        language: Language?,
        toolchain: CcToolchainProvider,
        cppConfiguration: CppConfiguration
    ): FeatureConfiguration {
        val allRequestedFeaturesBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
        val unsupportedFeaturesBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
        unsupportedFeaturesBuilder.addAll(unsupportedFeatures)
        if (!toolchain.supportsHeaderParsing()) {
            // TODO(b/159096411): Remove once supports_header_parsing has been removed from the
            // cc_toolchain rule.
            unsupportedFeaturesBuilder.add(CppRuleClasses.PARSE_HEADERS)
        }

        if (language != com.google.devtools.build.lib.rules.cpp.CcCommon.Language.OBJC && language != com.google.devtools.build.lib.rules.cpp.CcCommon.Language.OBJCPP && toolchain.getCcInfoCcCompilationContext()
                .getCppModuleMap() == null
        ) {
            unsupportedFeaturesBuilder.add(CppRuleClasses.MODULE_MAPS)
        }

        if (cppConfiguration.forcePic()) {
            if (unsupportedFeatures.contains(CppRuleClasses.SUPPORTS_PIC)) {
                throw net.starlark.java.eval.EvalException(PIC_CONFIGURATION_ERROR)
            }
            allRequestedFeaturesBuilder.add(CppRuleClasses.SUPPORTS_PIC)
        }

        if (cppConfiguration.appleGenerateDsym()) {
            allRequestedFeaturesBuilder.add(CppRuleClasses.GENERATE_DSYM_FILE_FEATURE_NAME)
        } else {
            allRequestedFeaturesBuilder.add(CppRuleClasses.NO_GENERATE_DEBUG_SYMBOLS_FEATURE_NAME)
        }

        if (language == com.google.devtools.build.lib.rules.cpp.CcCommon.Language.OBJC || language == com.google.devtools.build.lib.rules.cpp.CcCommon.Language.OBJCPP) {
            allRequestedFeaturesBuilder.add(CppRuleClasses.LANG_OBJC)
            if (cppConfiguration.objcGenerateLinkmap()) {
                allRequestedFeaturesBuilder.add(CppRuleClasses.GENERATE_LINKMAP_FEATURE_NAME)
            }
            if (cppConfiguration.objcShouldStripBinary()) {
                allRequestedFeaturesBuilder.add(CppRuleClasses.DEAD_STRIP_FEATURE_NAME)
            }
        }

        val allUnsupportedFeatures: com.google.common.collect.ImmutableSet<String?> = unsupportedFeaturesBuilder.build()

        val allFeatures: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
                .addAll(com.google.common.collect.ImmutableSet.of<E?>(cppConfiguration.getCompilationMode().toString()))
                .addAll(DEFAULT_ACTION_CONFIGS)
                .addAll(requestedFeatures)
                .addAll(toolchain.getFeatures().getDefaultFeaturesAndActionConfigs())

        if (language == com.google.devtools.build.lib.rules.cpp.CcCommon.Language.OBJC || language == com.google.devtools.build.lib.rules.cpp.CcCommon.Language.OBJCPP) {
            allFeatures.addAll(OBJC_ACTIONS)
        }

        if (!cppConfiguration.dontEnableHostNonhost()) {
            if (toolchain.isToolConfiguration()) {
                allFeatures.add("host")
            } else {
                allFeatures.add("nonhost")
            }
        }

        allFeatures.addAll(getCoverageFeatures(cppConfiguration))

        if (!allUnsupportedFeatures.contains(CppRuleClasses.FDO_INSTRUMENT)) {
            if (cppConfiguration.getFdoInstrument() != null) {
                allFeatures.add(CppRuleClasses.FDO_INSTRUMENT)
            } else {
                if (cppConfiguration.getCSFdoInstrument() != null) {
                    allFeatures.add(CppRuleClasses.CS_FDO_INSTRUMENT)
                }
            }
        }

        val branchFdoProvider: BranchFdoProfile? = toolchain.getFdoContext().getBranchFdoProfile()

        val enablePropellerOptimize =
            (toolchain.getFdoContext().getPropellerOptimizeInputFile() != null
                    && (toolchain.getFdoContext().getPropellerOptimizeInputFile().getCcArtifact() != null
                    || (toolchain.getFdoContext().getPropellerOptimizeInputFile().getLdArtifact()
                    != null)))

        if (branchFdoProvider != null && cppConfiguration.getCompilationMode() === CompilationMode.OPT) {
            if ((branchFdoProvider.isLlvmFdo() || branchFdoProvider.isLlvmCSFdo())
                && !allUnsupportedFeatures.contains(CppRuleClasses.FDO_OPTIMIZE)
            ) {
                allFeatures.add(CppRuleClasses.FDO_OPTIMIZE)
                // Support implicit enabling of ThinLTO for FDO unless it has been explicitly disabled.
                if (!allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
                    allFeatures.add(CppRuleClasses.ENABLE_FDO_THINLTO)
                }

                // Support implicit enabling of split functions for FDO unless it has been explicitly
                // disabled
                // or propeller_optimize is used. propeller_optimize must also disable split functions as
                // they are mutually exclusive.
                if (!allUnsupportedFeatures.contains(CppRuleClasses.SPLIT_FUNCTIONS)
                    && !enablePropellerOptimize
                ) {
                    allFeatures.add(CppRuleClasses.ENABLE_FDO_SPLIT_FUNCTIONS)
                }
            }
            if (branchFdoProvider.isLlvmCSFdo()) {
                allFeatures.add(CppRuleClasses.CS_FDO_OPTIMIZE)
            }
            if (branchFdoProvider.isAutoFdo()) {
                allFeatures.add(CppRuleClasses.AUTOFDO)
                // Support implicit enabling of Memprof for AFDO unless it has been disabled.
                if (!allUnsupportedFeatures.contains(CppRuleClasses.MEMPROF_OPTIMIZE)) {
                    allFeatures.add(CppRuleClasses.ENABLE_AUTOFDO_MEMPROF_OPTIMIZE)
                }
                // Support implicit enabling of ThinLTO for AFDO unless it has been disabled.
                if (!allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
                    allFeatures.add(CppRuleClasses.ENABLE_AFDO_THINLTO)
                }
                // Support implicit enabling of FSAFDO for AFDO unless it has been disabled.
                if (!allUnsupportedFeatures.contains(CppRuleClasses.FSAFDO)) {
                    allFeatures.add(CppRuleClasses.ENABLE_FSAFDO)
                    // Support implicit enabling of MFS for FSAFDO unless it has been disabled.
                    // We are reusing the "ENABLE_FDO_SPLIT_FUNCTIONS" feature here.
                    if (!allUnsupportedFeatures.contains(CppRuleClasses.SPLIT_FUNCTIONS)) {
                        allFeatures.add(CppRuleClasses.ENABLE_FDO_SPLIT_FUNCTIONS)
                    }
                }
            }
            if (branchFdoProvider.isAutoXBinaryFdo()) {
                allFeatures.add(CppRuleClasses.XBINARYFDO)
                // Support implicit enabling of ThinLTO for XFDO unless it has been explicitly disabled.
                if (!allUnsupportedFeatures.contains(CppRuleClasses.THIN_LTO)) {
                    allFeatures.add(CppRuleClasses.ENABLE_XFDO_THINLTO)
                }
            }
        }
        if (cppConfiguration.getFdoPrefetchHintsLabel() != null) {
            allRequestedFeaturesBuilder.add(CppRuleClasses.FDO_PREFETCH_HINTS)
        }

        if (enablePropellerOptimize) {
            allRequestedFeaturesBuilder.add(CppRuleClasses.PROPELLER_OPTIMIZE)
        }

        for (feature in allFeatures.build()) {
            if (!allUnsupportedFeatures.contains(feature)) {
                allRequestedFeaturesBuilder.add(feature)
            }
        }

        try {
            val featureConfiguration: FeatureConfiguration =
                toolchain.getFeatures().getFeatureConfiguration(allRequestedFeaturesBuilder.build())
            for (feature in unsupportedFeatures) {
                if (featureConfiguration.isEnabled(feature)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "The C++ toolchain '%s' unconditionally implies feature '%s', which is unsupported"
                                + " by this rule. This is most likely a misconfiguration in the C++ toolchain.",
                        toolchain.getCcToolchainLabel(), feature
                    )
                }
            }
            if (cppConfiguration.forcePic()
                && !featureConfiguration.isEnabled(CppRuleClasses.PIC) && !featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)
            ) {
                throw net.starlark.java.eval.EvalException(PIC_CONFIGURATION_ERROR)
            }
            return featureConfiguration
        } catch (ex: CollidingProvidesException) {
            throw net.starlark.java.eval.EvalException(ex)
        }
    }

    /** An enum for the list of supported languages.  */
    enum class Language(representation: String) {
        CPP("c++"),
        OBJC("objc"),
        OBJCPP("objc++");

        val representation: String?

        init {
            this.representation = representation
        }
    }

    /** A filter that removes copts from a c++ compile action according to a nocopts regex.  */
    class CoptsFilter private constructor(noCoptsPattern: java.util.regex.Pattern?, allPasses: Boolean) :
        net.starlark.java.eval.StarlarkValue {
        private val noCoptsPattern: java.util.regex.Pattern?
        private val allPasses: Boolean

        init {
            this.noCoptsPattern = noCoptsPattern
            this.allPasses = allPasses
        }

        /**
         * Returns true if the provided string passes through the filter, or false if it should be
         * removed.
         */
        fun passesFilter(flag: String?): Boolean {
            if (allPasses) {
                return true
            } else {
                return !noCoptsPattern.matcher(flag).matches()
            }
        }

        val isImmutable: Boolean
            get() = true

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("CoptsFilter(noCoptsPattern=")
            printer.append(if (noCoptsPattern == null) "null" else noCoptsPattern.pattern())
            printer.append(", allPasses=")
            printer.append(allPasses.toString())
            printer.append(")")
        }

        companion object {
            /** Creates a filter that filters all matches to a regex.  */
            fun fromRegex(noCoptsPattern: java.util.regex.Pattern?): CoptsFilter {
                return CoptsFilter(noCoptsPattern, false)
            }

            /** Creates a filter that passes on all inputs.  */
            @kotlin.jvm.JvmStatic
            fun alwaysPasses(): CoptsFilter {
                return CoptsFilter(null, true)
            }
        }
    }
}
