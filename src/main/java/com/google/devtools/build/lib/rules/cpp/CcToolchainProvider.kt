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

import com.google.devtools.build.lib.actions.Artifact

/** Information about a C++ compiler used by the `cc_*` rules.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class CcToolchainProvider private constructor(value: StarlarkInfo) {
    private class RulesCcCcToolchainInfoProvider : CcToolchainInfoProvider(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked("//cc/private/rules_impl:cc_toolchain_info.bzl")
        ),
        STARLARK_NAME
    )

    private class BazelCcToolchainInfoProvider : CcToolchainInfoProvider(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                "@rules_cc+//cc/private/rules_impl:cc_toolchain_info.bzl"
            )
        ),
        STARLARK_NAME
    )

    private class GoogleCcToolchainInfoProvider : CcToolchainInfoProvider(
        BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                "//third_party/bazel_rules/rules_cc/cc/private/rules_impl:cc_toolchain_info.bzl"
            )
        ),
        STARLARK_NAME
    )

    /** Provider class for [CcToolchainProvider] objects.  */
    abstract class CcToolchainInfoProvider
    protected constructor(loadKey: BzlLoadValue.Key?, name: String?) :
        StarlarkProviderWrapper<CcToolchainProvider?>(loadKey, name), Provider {
        @Throws(net.starlark.java.eval.EvalException::class)
        fun wrapOrThrowEvalException(value: Info): CcToolchainProvider {
            if (value is StarlarkInfoWithSchema
                && value.getProvider().getKey().equals(getKey())
            ) {
                return CcToolchainProvider(value as StarlarkInfo)
            } else {
                throw net.starlark.java.eval.EvalException(
                    java.lang.String.format(
                        "got value of type '%s', want 'CcToolchainInfo'",
                        net.starlark.java.eval.Starlark.type(value)
                    )
                )
            }
        }

        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info): CcToolchainProvider {
            if (value is StarlarkInfoWithSchema
                && value.getProvider().getKey().equals(getKey())
            ) {
                return CcToolchainProvider(value as StarlarkInfo)
            } else {
                throw RuleErrorException(
                    "got value of type '" + net.starlark.java.eval.Starlark.type(value) + "', want 'CcToolchainInfo'"
                )
            }
        }

        val isExported: Boolean
            get() = true

        val printableName: String
            get() = STARLARK_NAME

        val location: net.starlark.java.syntax.Location
            get() = net.starlark.java.syntax.Location.BUILTIN
    }

    private val value: StarlarkInfo

    init {
        this.value = value
    }

    @com.google.common.annotations.VisibleForTesting
    fun getValue(): StarlarkInfo {
        return value
    }

    // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/cc_helper_internal.bzl)
    /** Whether the toolchains supports header parsing.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun supportsHeaderParsing(): Boolean {
        return value.getValue("_supports_header_parsing", Boolean::class.java)
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val toolPaths: com.google.common.collect.ImmutableMap<String?, String?>
        get() = com.google.common.collect.ImmutableMap.copyOf<K?, V?>(
            net.starlark.java.eval.Dict.cast<K?, V?>(
                value.getValue("_tool_paths", net.starlark.java.eval.Dict::class.java),
                String::class.java,
                String::class.java,
                "_tool_paths"
            )
        )

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val builtInIncludeDirectories: com.google.common.collect.ImmutableList<PathFragment?>?
        get() {
            val seq: net.starlark.java.eval.Sequence<String?>? =
                net.starlark.java.eval.Sequence.cast<T?>(
                    value.getValue("built_in_include_directories", net.starlark.java.eval.Sequence::class.java),
                    String::class.java,
                    "built_in_include_directories"
                )
            return builtinIncludeDirectoriesCache.get(seq)
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val toolchainIdentifier: String
        /** Returns the identifier of the toolchain as specified in the `CToolchain` proto.  */
        get() = value.getValue("toolchain_id", String::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val allFiles: NestedSet<Artifact?>
        /** Returns all the files in Crosstool.  */
        get() {
            try {
                return value.getValue("all_files", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: TypeException) {
                throw net.starlark.java.eval.EvalException(e)
            }
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val allFilesIncludingLibc: NestedSet<Artifact?>
        /** Returns all the files in Crosstool + libc.  */
        get() {
            try {
                return value.getValue("_all_files_including_libc", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: TypeException) {
                throw net.starlark.java.eval.EvalException(e)
            }
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val compilerFiles: NestedSet<Artifact?>
        /** Returns the files necessary for compilation.  */
        get() {
            try {
                return value.getValue("_compiler_files", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: TypeException) {
                throw net.starlark.java.eval.EvalException(e)
            }
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val compilerFilesWithoutIncludes: NestedSet<Artifact?>
        /**
         * Returns the files necessary for compilation excluding headers, assuming that included files
         * will be discovered by input discovery.
         */
        get() {
            try {
                return value
                    .getValue("_compiler_files_without_includes", Depset::class.java)
                    .getSet(Artifact::class.java)
            } catch (e: TypeException) {
                throw net.starlark.java.eval.EvalException(e)
            }
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val asFiles: NestedSet<Artifact?>
        /**
         * Returns the files necessary for an 'as' invocation. May be empty if the CROSSTOOL file does not
         * define as_files.
         */
        get() {
            try {
                return value.getValue("_as_files", Depset::class.java).getSet(Artifact::class.java)
            } catch (e: TypeException) {
                throw net.starlark.java.eval.EvalException(e)
            }
        }

    @get:Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    val arFiles: NestedSet<Artifact?>
        /**
         * Returns the files necessary for an 'ar' invocation. May be empty if the CROSSTOOL file does not
         * define ar_files.
         */
        get() = value.getValue("_ar_files", Depset::class.java).getSet(Artifact::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    val linkerFiles: NestedSet<Artifact?>
        /** Returns the files necessary for linking, including the files needed for libc.  */
        get() = value.getValue("_linker_files", Depset::class.java).getSet(Artifact::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    @get:com.google.common.annotations.VisibleForTesting
    val coverageFiles: NestedSet<Artifact?>
        /** Returns the files necessary for capturing code coverage.  */
        get() = value.getValue("_coverage_files", Depset::class.java).getSet(Artifact::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    val staticRuntimeLinkInputs: NestedSet<Artifact?>?
        get() = nullOrDepset(value, "_static_runtime_lib_depset")

    @get:Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
    val dynamicRuntimeLinkInputs: NestedSet<Artifact?>?
        get() = nullOrDepset(value, "_dynamic_runtime_lib_depset")

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val dynamicRuntimeSolibDir: PathFragment?
        /**
         * Returns the name of the directory where the solib symlinks for the dynamic runtime libraries
         * live. The directory itself will be under the root of the exec configuration in the 'bin'
         * directory.
         */
        get() = PathFragment.create(value.getValue("dynamic_runtime_solib_dir", String::class.java))

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val ccInfoCcCompilationContext: CcCompilationContext
        /** Returns `CcCompilationContext` from the `CcInfo` for the toolchain.  */
        get() = CcCompilationContext.Companion.of(
            value
                .getValue("_cc_info", StarlarkInfo::class.java)
                .getValue("compilation_context", StarlarkInfo::class.java)
        )

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val features: CcToolchainFeatures?
        /** Returns the configured features of the toolchain.  */
        get() = value.getValue("_toolchain_features", CcToolchainFeatures::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val ccToolchainLabel: Label
        get() = value.getValue("_toolchain_label", Label::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val solibDirectory: String
        /**
         * Return the name of the directory (relative to the bin directory) that holds mangled links to
         * shared libraries. This name is always set to the '`_solib_<cpu_archictecture_name>`.
         */
        get() = value.getValue("_solib_dir", String::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val cSFdoInstrument: String?
        /** Return context-sensitive fdo instrumentation path.  */
        get() {
            val cppConfiguration: CppConfiguration =
                value.getValue("_cpp_configuration", CppConfiguration::class.java)
            return cppConfiguration.getCSFdoInstrument()
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val buildVars: CcToolchainVariables
        get() = getValue().getValue("_build_variables", CcToolchainVariables::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val builtinIncludeFiles: com.google.common.collect.ImmutableList<Artifact?>?
        /**
         * Return the set of include files that may be included even if they are not mentioned in the
         * source file or any of the headers included by it.
         */
        get() = net.starlark.java.eval.Sequence.cast<T?>(
            value.getValue("_builtin_include_files", net.starlark.java.eval.Sequence::class.java),
            Artifact::class.java,
            "_builtin_include_files"
        )
            .getImmutableList()

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val grepIncludes: Artifact?
        /** Returns the grep-includes tool which is needing during linking because of linkstamping.  */
        get() = value.getNoneableValue("_grep_includes", Artifact::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val sysroot: String?
        get() {
            val sysroot: PathFragment? = nullOrPathFragment(value, "sysroot")
            return if (sysroot != null) sysroot.getPathString() else null
        }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val sysrootPathFragment: PathFragment?
        get() = nullOrPathFragment(value, "sysroot")

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:com.google.common.annotations.VisibleForTesting
    val abi: String
        /**
         * Returns the abi we're using, which is a gcc version. E.g.: "gcc-3.4". Note that in practice we
         * might be using gcc-3.4 as ABI even when compiling with gcc-4.1.0, because ABIs are backwards
         * compatible.
         */
        get() = value.getValue("_abi", String::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    @get:Deprecated("Use the CC_FLAGS from feature configuration instead.")
    val legacyCcFlagsMakeVariable: String
        /**
         * Returns the legacy value of the CC_FLAGS Make variable.
         * 
         */
        get() = value.getValue("_legacy_cc_flags_make_variable", String::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val fdoContext: FdoContext
        get() = FdoContext(value.getValue("_fdo_context", StructImpl::class.java))

    // Not all of CcToolchainProvider is exposed to Starlark, which makes implementing deep equality
    // impossible: if Java-only parts are considered, the behavior is surprising in Starlark, if they
    // are not, the behavior is surprising in Java. Thus, object identity it is.
    override fun equals(other: Any?): Boolean {
        return other === this
    }

    override fun hashCode(): Int {
        return java.lang.System.identityHashCode(this)
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val isToolConfiguration: Boolean
        get() = value.getValue("_is_tool_configuration", Boolean::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val allowlistForLayeringCheck: PackageSpecificationProvider
        get() = value.getValue("_allowlist_for_layering_check", PackageSpecificationProvider::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val ccBuildInfoTranslator: OutputGroupInfo
        get() = value.getValue("_build_info_files", OutputGroupInfo::class.java)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val cppConfiguration: CppConfiguration
        get() = value.getValue("_cpp_configuration", CppConfiguration::class.java)

    companion object {
        const val STARLARK_NAME: String = "CcToolchainInfo"

        // provider when rules_cc itself is the main module
        private val RULES_CC_PROVIDER: CcToolchainInfoProvider = RulesCcCcToolchainInfoProvider()
        val BAZEL_PROVIDER: CcToolchainInfoProvider = BazelCcToolchainInfoProvider()
        val GOOGLE_PROVIDER: CcToolchainInfoProvider = GoogleCcToolchainInfoProvider()

        @Throws(net.starlark.java.eval.EvalException::class)
        fun wrapOrThrowEvalException(toolchainInfo: Info): CcToolchainProvider {
            if (toolchainInfo.getProvider().getKey().equals(BAZEL_PROVIDER.getKey())) {
                return BAZEL_PROVIDER.wrapOrThrowEvalException(toolchainInfo)
            }
            if (toolchainInfo.getProvider().getKey().equals(GOOGLE_PROVIDER.getKey())) {
                return GOOGLE_PROVIDER.wrapOrThrowEvalException(toolchainInfo)
            }
            return RULES_CC_PROVIDER.wrapOrThrowEvalException(toolchainInfo)
        }

        @Throws(RuleErrorException::class)
        fun wrap(toolchainInfo: Info): CcToolchainProvider {
            if (toolchainInfo.getProvider().getKey().equals(BAZEL_PROVIDER.getKey())) {
                return BAZEL_PROVIDER.wrap(toolchainInfo)
            }
            if (toolchainInfo.getProvider().getKey().equals(GOOGLE_PROVIDER.getKey())) {
                return GOOGLE_PROVIDER.wrap(toolchainInfo)
            }
            return RULES_CC_PROVIDER.wrap(toolchainInfo)
        }

        @Throws(RuleErrorException::class)
        fun getFromTarget(target: ConfiguredTarget): CcToolchainProvider? {
            var provider: CcToolchainProvider? = target.get(RULES_CC_PROVIDER)
            if (provider == null) {
                provider = target.get(BAZEL_PROVIDER)
            }
            if (provider == null) {
                provider = target.get(GOOGLE_PROVIDER)
            }
            return provider
        }

        @Throws(net.starlark.java.eval.EvalException::class, TypeException::class)
        private fun nullOrDepset(value: StarlarkInfo, key: String?): NestedSet<Artifact?>? {
            if (value.getValue(key) == null || value.getValue(key) === net.starlark.java.eval.Starlark.NONE) {
                return null
            }
            return value.getValue(key, Depset::class.java).getSet(Artifact::class.java)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun nullOrPathFragment(value: StarlarkInfo, key: String?): PathFragment? {
            if (value.getValue(key) == null || value.getValue(key) === net.starlark.java.eval.Starlark.NONE) {
                return null
            }
            return PathFragment.create(value.getValue(key, String::class.java))
        }

        // Ensures that we use a canonical ImmutableList<PathFragment> instance to save memory.
        private val builtinIncludeDirectoriesCache: com.github.benmanes.caffeine.cache.LoadingCache<net.starlark.java.eval.Sequence<String?>?, com.google.common.collect.ImmutableList<PathFragment?>?> =
            Caffeine.newBuilder()
                .weakKeys()
                .build<net.starlark.java.eval.Sequence<String?>?, com.google.common.collect.ImmutableList<PathFragment?>?>(
                    com.github.benmanes.caffeine.cache.CacheLoader { seq: net.starlark.java.eval.Sequence<kotlin.String?>? ->
                        seq.stream()
                            .map<PathFragment?>(java.util.function.Function { path: String? -> PathFragment.create(path) })
                            .collect(TODO("Cannot convert element"))<PathFragment> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()
                    })

        fun create(value: StarlarkInfo): CcToolchainProvider {
            return CcToolchainProvider(value)
        }

        // LINT.IfChange
        /**
         * Determines if we should apply -fPIC for this rule's C++ compilations. This determination is
         * generally made by the global C++ configuration settings "needsPic" and "usePicForBinaries".
         * However, an individual rule may override these settings by applying -fPIC" to its "nocopts"
         * attribute. This allows incompatible rules to "opt out" of global PIC settings (see bug:
         * "Provide a way to turn off -fPIC for targets that can't be built that way").
         * 
         * @return true if this rule's compilations should apply -fPIC, false otherwise
         */
        fun usePicForDynamicLibraries(
            cppConfiguration: CppConfiguration, featureConfiguration: FeatureConfiguration
        ): Boolean {
            return cppConfiguration.forcePic()
                    || featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)
        }

        /**
         * Returns true if headers should be parsed in this build.
         * 
         * 
         * This means headers in 'srcs' and 'hdrs' will be "compiled" using [CppCompileAction]).
         * It will run compiler's parser to ensure the header is self-contained. This is required for
         * layering_check to work.
         */
        fun shouldProcessHeaders(
            featureConfiguration: FeatureConfiguration, cppConfiguration: CppConfiguration?
        ): Boolean {
            return featureConfiguration.isEnabled(CppRuleClasses.PARSE_HEADERS)
        }

        /**
         * Returns the path String that is either absolute or relative to the execution root that can be
         * used to execute the given tool.
         * 
         * @throws RuleErrorException when the tool is not specified by the toolchain.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getToolPathString(
            toolPaths: com.google.common.collect.ImmutableMap<String?, String>,
            tool: com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool,
            ccToolchainLabel: Label?,
            toolchainIdentifier: String?
        ): String {
            val toolPath = getToolPathStringOrNull(toolPaths, tool)
            if (toolPath == null) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "cc_toolchain '%s' with identifier '%s' doesn't define a tool path for '%s'",
                    ccToolchainLabel, toolchainIdentifier, tool.getNamePart()
                )
            }
            return toolPath
        }

        /**
         * Returns the path string that is either absolute or relative to the execution root that can be
         * used to execute the given tool.
         */
        fun getToolPathStringOrNull(
            toolPaths: com.google.common.collect.ImmutableMap<String?, String>,
            tool: com.google.devtools.build.lib.rules.cpp.CppConfiguration.Tool
        ): String {
            return toolPaths.get(tool.getNamePart())
        }

        /** Returns whether this toolchain supports interface shared libraries.  */ // TODO(gnish): Move this to FeatureConfiguration.
        fun supportsInterfaceSharedLibraries(
            featureConfiguration: FeatureConfiguration
        ): Boolean {
            return featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES)
        }
    }
}
