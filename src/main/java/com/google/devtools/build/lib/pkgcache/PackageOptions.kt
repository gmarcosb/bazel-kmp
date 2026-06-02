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
package com.google.devtools.build.lib.pkgcache


import com.google.devtools.build.lib.cmdline.LabelSyntaxException

/** Options for configuring Packages -- loading and default behaviors.  */
@com.google.devtools.common.options.OptionsClass
abstract class PackageOptions : com.google.devtools.common.options.OptionsBase() {
    /** Converter for the `--default_visibility` option.  */
    class DefaultVisibilityConverter : com.google.devtools.common.options.Converter.Contextless<RuleVisibility?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): RuleVisibility {
            return when (input) {
                "public" -> RuleVisibility.Companion.PUBLIC
                "private" -> RuleVisibility.Companion.PRIVATE
                else -> throw com.google.devtools.common.options.OptionsParsingException(
                    "Not a valid default visibility: '" + input + "' (should be 'public' or 'private'"
                )
            }
        }

        override fun getTypeDescription(): String {
            return "default visibility"
        }
    }

    /** Converter for globbing threads.  */
    class ParallelismConverter :
        ResourceConverter.IntegerConverter( /* auto= */ResourceConverter.HOST_CPUS_SUPPLIER,  /* minValue= */
            1,  /* maxValue= */
            java.lang.Integer.MAX_VALUE
        )

    @com.google.devtools.common.options.Option(
        name = "package_path",
        defaultValue = "%workspace%",
        converter = com.google.devtools.common.options.Converters.ColonSeparatedOptionListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("A colon-separated list of where to look for packages. "
                + "Elements beginning with '%workspace%' are relative to the enclosing "
                + "workspace. If omitted or empty, the default is the output of "
                + "'bazel info default-package-path'.")
    )
    abstract fun getPackagePath(): MutableList<String?>?

    abstract fun setPackagePath(value: MutableList<String?>?)

    @com.google.devtools.common.options.Option(
        name = "show_loading_progress",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "If enabled, causes Bazel to print \"Loading package:\" messages."
    )
    abstract fun getShowLoadingProgress(): Boolean

    abstract fun setShowLoadingProgress(value: Boolean)

    @com.google.devtools.common.options.Option(
        name = "deleted_packages",
        allowMultiple = true,
        defaultValue = "null",
        converter = CommaSeparatedPackageNameListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("A comma-separated list of names of packages which the "
                + "build system will consider non-existent, even if they are "
                + "visible somewhere on the package path.\n"
                + "Use this option when deleting a subpackage 'x/y' of an "
                + "existing package 'x'.  For example, after deleting x/y/BUILD "
                + "in your client, the build system may complain if it "
                + "encounters a label '//x:y/z' if that is still provided by another "
                + "package_path entry.  Specifying --deleted_packages x/y avoids this "
                + "problem.")
    )
    abstract fun getDeletedPackages(): MutableList<PackageIdentifier?>?

    @com.google.devtools.common.options.Option(
        name = "default_visibility",
        defaultValue = "private",
        converter = DefaultVisibilityConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = "Default visibility for packages that don't set it explicitly ('public' or 'private')."
    )
    abstract fun getDefaultVisibility(): RuleVisibility?

    abstract fun setDefaultVisibility(value: RuleVisibility?)

    @com.google.devtools.common.options.Option(
        name = "incompatible_enforce_config_setting_visibility",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If true, enforce config_setting visibility restrictions. If false, every "
                + "config_setting is visible to every target. See "
                + "https://github.com/bazelbuild/bazel/issues/12932.")
    )
    abstract fun getEnforceConfigSettingVisibility(): Boolean

    @com.google.devtools.common.options.Option(
        name = "incompatible_config_setting_private_default_visibility",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.STARLARK_SEMANTICS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If incompatible_enforce_config_setting_visibility=false, this is a noop. Else, if this"
                + " flag is false, any config_setting without an explicit visibility attribute is"
                + " //visibility:public. If this flag is true, config_setting follows the same"
                + " visibility logic as all other rules. See"
                + " https://github.com/bazelbuild/bazel/issues/12933.")
    )
    abstract fun getConfigSettingPrivateDefaultVisibility(): Boolean

    @com.google.devtools.common.options.Option(
        name = "legacy_globbing_threads",
        defaultValue = "100",
        converter = com.google.devtools.build.lib.pkgcache.PackageOptions.ParallelismConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Number of threads to use for glob evaluation. Takes "
                + ResourceConverter.FLAG_SYNTAX
                + ". \"auto\" means to use a reasonable value derived from the machine's hardware"
                + " profile (e.g. the number of processors).")
    )
    abstract fun getGlobbingThreads(): Int

    abstract fun setGlobbingThreads(value: Int)

    @com.google.devtools.common.options.Option(
        name = "experimental_max_directories_to_eagerly_visit_in_globbing",
        defaultValue = "-1",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("If non-negative, the first time a glob is evaluated in a package, the subdirectories of"
                + " the package will be traversed in order to warm filesystem caches and compensate"
                + " for lack of parallelism in globbing. At most this many directories will be"
                + " visited.")
    )
    abstract fun getMaxDirectoriesToEagerlyVisitInGlobbing(): Int

    @com.google.devtools.common.options.Option(
        name = "fetch",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Allows the command to fetch external dependencies. If set to false, the command will"
                + " utilize any cached version of the dependency, and if none exists, the command"
                + " will result in failure.")
    )
    abstract fun getFetch(): Boolean

    @com.google.devtools.common.options.Option(
        name = "experimental_check_output_files",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Check for modifications made to the output files of a build. Consider setting "
                + "this flag to false if you don't expect these files to change outside of bazel "
                + "since it will speed up subsequent runs as they won't have to check a "
                + "previous run's cache.")
    )
    abstract fun getCheckOutputFiles(): Boolean

    abstract fun setCheckOutputFiles(value: Boolean)

    @com.google.devtools.common.options.Option(
        name = "experimental_check_external_other_files",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Check for modifications made to the non-output, non-repo external files, e.g. host"
                + " files.")
    )
    abstract fun getCheckExternalOtherFiles(): Boolean

    abstract fun setCheckExternalOtherFiles(value: Boolean)

    // TODO(https://github.com/bazelbuild/bazel/issues/25539) - at present, lazy macro expansion is
    // incompatible with non-finalizer use of native.existing_rules(). Once we can load all packages
    // in lazy macro expansion mode, we might evolve this option to be an allowlist/denylist for
    // performance reasons - which would mean supporting negation or package_group()-style
    // subpackage patterns.
    @com.google.devtools.common.options.Option(
        name = "experimental_lazy_macro_expansion_packages",
        defaultValue = "",
        converter = OptionConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.LOSES_INCREMENTAL_STATE],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = "List of packages in which symbolic macro are expanded only if necessary."
    )
    abstract fun getLazyMacroExpansionPackages(): LazyMacroExpansionPackages?

    abstract fun setLazyMacroExpansionPackages(value: LazyMacroExpansionPackages?)

    /** A converter from strings containing comma-separated names of packages to lists of strings.  */
    class CommaSeparatedPackageNameListConverter

        : com.google.devtools.common.options.Converter.Contextless<MutableList<PackageIdentifier?>?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): MutableList<PackageIdentifier?> {
            if (com.google.common.base.Strings.isNullOrEmpty(input)) {
                return com.google.common.collect.ImmutableList.of<PackageIdentifier?>()
            }
            val list: com.google.common.collect.ImmutableList.Builder<PackageIdentifier?> =
                com.google.common.collect.ImmutableList.builder<PackageIdentifier?>()
            for (s in COMMA_SPLITTER.split(input)) {
                try {
                    list.add(PackageIdentifier.parse(s))
                } catch (e: LabelSyntaxException) {
                    throw com.google.devtools.common.options.OptionsParsingException(e.getMessage())
                }
            }
            return list.build()
        }

        override fun getTypeDescription(): String {
            return "comma-separated list of package names"
        }

        companion object {
            private val COMMA_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on(',')
        }
    }

    fun getDeletedPackagesOrEmptySet(): com.google.common.collect.ImmutableSet<PackageIdentifier?> {
        if (getDeletedPackages() == null) {
            return com.google.common.collect.ImmutableSet.of<PackageIdentifier?>()
        }
        return com.google.common.collect.ImmutableSet.copyOf<PackageIdentifier?>(getDeletedPackages())
    }

    /**
     * The set of packages in which symbolic macros are to be expanded lazily. Used by Skyframe and by
     * PackageLoader.
     */
    interface LazyMacroExpansionPackages {
        /** Returns true if symbolic macros in the given package should be expanded lazily.  */
        fun contains(packageId: PackageIdentifier?): Boolean

        /** [Converter] for [LazyMacroExpansionPackages].  */
        class OptionConverter :
            com.google.devtools.common.options.Converter.Contextless<LazyMacroExpansionPackages?>() {
            @Throws(com.google.devtools.common.options.OptionsParsingException::class)
            override fun convert(input: String): LazyMacroExpansionPackages {
                val strings: com.google.common.collect.ImmutableList<String?> = stringConverter.convert(input)
                if (strings.isEmpty()) {
                    return NONE
                } else if (strings.contains("*")) {
                    return ALL
                } else {
                    val packageIds: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
                        com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()
                    for (s in strings) {
                        try {
                            packageIds.add(PackageIdentifier.parse(s))
                        } catch (e: LabelSyntaxException) {
                            throw com.google.devtools.common.options.OptionsParsingException(e.getMessage())
                        }
                    }
                    return LazyMacroExpansionPackagesSet(packageIds.build())
                }
            }

            override fun getTypeDescription(): String {
                return "comma-separated list of package names; or '*' to indicate all packages"
            }

            companion object {
                private val stringConverter: com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter =
                    com.google.devtools.common.options.Converters.CommaSeparatedNonEmptyOptionListConverter()
            }
        }

        companion object {
            /**
             * A [LazyMacroExpansionPackages] indicating that no packages should have symbolic macros
             * expanded lazily.
             */
            @kotlin.jvm.JvmField
            val NONE: LazyMacroExpansionPackages =
                LazyMacroExpansionPackagesSet(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())

            /**
             * A [LazyMacroExpansionPackages] indicating that all packages should have symbolic macros
             * expanded lazily.
             */
            @kotlin.jvm.JvmField
            val ALL: LazyMacroExpansionPackages = object : LazyMacroExpansionPackages {
                override fun contains(packageId: PackageIdentifier?): Boolean {
                    return true
                }
            }
        }
    }

    private class LazyMacroExpansionPackagesSet(packageIds: com.google.common.collect.ImmutableSet<PackageIdentifier?>) :
        LazyMacroExpansionPackages {
        private val packageIds: com.google.common.collect.ImmutableSet<PackageIdentifier?>

        init {
            this.packageIds = packageIds
        }

        override fun contains(packageId: PackageIdentifier?): Boolean {
            return packageIds.contains(packageId)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            return o is LazyMacroExpansionPackagesSet
                    && packageIds == o.packageIds
        }

        override fun hashCode(): Int {
            return java.util.Objects.hashCode(packageIds)
        }

        override fun toString(): String {
            return java.lang.String.format("LazyMacroExpansionPackages[%s]", packageIds)
        }
    }
}
