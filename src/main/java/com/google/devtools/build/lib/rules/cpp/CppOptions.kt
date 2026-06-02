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

/** Command-line options for C++.  */
@com.google.devtools.common.options.OptionsClass
abstract class CppOptions : FragmentOptions() {
    /** Converts a comma-separated list of compilation mode settings to a properly typed List.  */
    class FissionOptionConverter :
        com.google.devtools.common.options.Converter.Contextless<MutableList<CompilationMode?>?>() {
        init {
            for (mode in CompilationMode.values()) {
                val modeString: String = modeConverter.reverseForStarlark(mode)
                // Check that 'yes' and 'no' are round-trippable.
                com.google.common.base.Preconditions.checkState(
                    modeString != "yes" && modeString != "no",
                    "The special values 'yes' and 'no' must not occur in the underlying %s enum",
                    CompilationMode::class.java
                )
            }
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableList<CompilationMode?> {
            val modes: com.google.common.collect.ImmutableSet.Builder<CompilationMode?> =
                com.google.common.collect.ImmutableSet.builder<CompilationMode?>()
            if (input == "yes") { // Special case: enable all modes.
                modes.add(CompilationMode.values())
            } else if (input != "no") { // "no" is another special case that disables all modes.
                for (mode in com.google.common.base.Splitter.on(',').split(input)) {
                    modes.add(modeConverter.convert(mode,  /* conversionContext= */null))
                }
            }
            return modes.build().asList()
        }

        val typeDescription: String
            get() = "a set of compilation modes"

        override fun starlarkConvertible(): Boolean {
            return true
        }

        override fun reverseForStarlark(converted: Any?): String? {
            val list:  // option and converter must match
                    MutableList<CompilationMode?> = converted as MutableList<CompilationMode?>
            // Canonicalize an empty list of modes as --fission=no, and a full list as --fission=yes. The
            // choice of canonicalization is arbitrary, but 'yes'/'no' are readable and very widely used
            // in practice.
            if (list.isEmpty()) {
                return "no"
            } else if (com.google.common.collect.ImmutableSet.copyOf<CompilationMode?>(list)
                    .size() == CompilationMode.values().length
            ) {
                return "yes"
            } else {
                return list.stream().map<Any?>(CompilationMode::toString).collect(Collectors.joining(","))
            }
        }

        companion object {
            private val modeConverter: CompilationMode.Converter = Converter()
        }
    }

    /** Converter for [DynamicMode]  */
    class DynamicModeConverter :
        com.google.devtools.common.options.EnumConverter<DynamicMode?>(DynamicMode::class.java, "dynamic mode")

    /** Converter for the --strip option.  */
    class StripModeConverter :
        com.google.devtools.common.options.EnumConverter<StripMode?>(StripMode::class.java, "strip mode")

    /**
     * Converts a String, which is a package label into a label that can be used for a LibcTop object.
     */
    class LibcTopLabelConverter : com.google.devtools.common.options.Converter<Label?> {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String, conversionContext: Any?): Label? {
            if (input == "default") {
                // This is needed for defining config_setting() values, the syntactic form
                // of which must be a String, to match absence of a --grte_top option.
                // "--grte_top=default" works on the command-line too,
                // but that's an inconsequential side-effect, not the intended purpose.
                return null
            } else if (!input.startsWith("//")) {
                throw com.google.devtools.common.options.OptionsParsingException("Not a label")
            }
            return Label.createUnvalidated(
                LABEL_CONVERTER.convert(input, conversionContext).getPackageIdentifier(), "everything"
            )
        }

        val typeDescription: String
            get() = "a label"

        companion object {
            private val LABEL_CONVERTER: LabelConverter = LabelConverter()
        }
    }

    @get:com.google.devtools.common.options.Option(
        name = "crosstool_top",
        defaultValue = "@bazel_tools//tools/cpp:toolchain",
        converter = LabelConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "No-op flag. Will be removed in a future release."
    )
    abstract val crosstoolTop: Label?

    @get:com.google.devtools.common.options.Option(
        name = "compiler",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "The C++ compiler to use for compiling the target."
    )
    abstract val cppCompiler: String?

    @get:com.google.devtools.common.options.Option(
        name = "host_compiler",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = "No-op flag. Will be removed in a future release."
    )
    abstract val hostCppCompiler: String?

    @get:com.google.devtools.common.options.Option(
        name = "cc_output_directory_tag",
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Specifies a suffix to be added to the configuration directory."
    )
    abstract val outputDirectoryTag: String?

    @get:com.google.devtools.common.options.Option(
        name = "minimum_os_version",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "The minimum OS version which your compilation targets."
    )
    abstract val minimumOsVersion: String?

    @get:com.google.devtools.common.options.Option(
        name = "start_end_lib",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = "Use the --start-lib/--end-lib ld options if supported by the toolchain."
    )
    abstract val useStartEndLib: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "interface_shared_objects",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS
        ],
        help = ("Use interface shared objects if supported by the toolchain. "
                + "All ELF toolchains currently support this setting.")
    )
    abstract val useInterfaceSharedObjects: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "fission",
        defaultValue = "no",
        converter = FissionOptionConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS
        ],
        help = ("Specifies which compilation modes use fission for C++ compilations and links.  May be"
                + " any combination of {'fastbuild', 'dbg', 'opt'} or the special values 'yes'  to"
                + " enable all modes and 'no' to disable all modes.")
    )
    abstract val fissionModes: MutableList<CompilationMode>?

    @get:com.google.devtools.common.options.Option(
        name = "build_test_dwp",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If enabled, when building C++ tests statically and with fission the .dwp file "
                + " for the test binary will be automatically built as well.")
    )
    abstract val buildTestDwp: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "dynamic_mode",
        defaultValue = "default",
        converter = DynamicModeConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Determines whether C++ binaries will be linked dynamically.  'default' means "
                + "Bazel will choose whether to link dynamically.  'fully' means all libraries "
                + "will be linked dynamically. 'off' means that all libraries will be linked "
                + "in mostly static mode.")
    )
    abstract val dynamicMode: DynamicMode?

    @get:com.google.devtools.common.options.Option(
        name = "force_pic",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If enabled, all C++ compilations produce position-independent code (\"-fPIC\"),"
                + " links prefer PIC pre-built libraries over non-PIC libraries, and links produce"
                + " position-independent executables (\"-pie\").")
    )
    abstract val forcePic: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "process_headers_in_dependencies",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = ("When building a target //a:a, process headers in all targets that //a:a depends "
                + "on (if header processing is enabled for the toolchain).")
    )
    abstract val processHeadersInDependencies: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "copt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional options to pass to gcc."
    )
    abstract val coptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "cxxopt",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional option to pass to gcc when compiling C++ source files."
    )
    abstract val cxxoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "conlyopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional option to pass to gcc when compiling C source files."
    )
    abstract val conlyoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "objccopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES],
        help = "Additional options to pass to gcc when compiling Objective-C/C++ source files."
    )
    abstract val objcoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "linkopt",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional option to pass to gcc when linking."
    )
    abstract val linkoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "ltoindexopt",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional option to pass to the LTO indexing step (under --features=thin_lto)."
    )
    abstract val ltoindexoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "ltobackendopt",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional option to pass to the LTO backend step (under --features=thin_lto)."
    )
    abstract val ltobackendoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "stripopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional options to pass to strip when generating a '<name>.stripped' binary."
    )
    abstract val stripoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "custom_malloc",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Specifies a custom malloc implementation. This setting overrides malloc "
                + "attributes in build rules."),
        converter = LabelConverter::class
    )
    abstract val customMalloc: Label?

    @get:com.google.devtools.common.options.Option(
        name = "legacy_whole_archive",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = ("Deprecated, superseded by --incompatible_remove_legacy_whole_archive "
                + "(see https://github.com/bazelbuild/bazel/issues/7362 for details). "
                + "When on, use --whole-archive for cc_binary rules that have "
                + "linkshared=True and either linkstatic=True or '-static' in linkopts. "
                + "This is for backwards compatibility only. "
                + "A better alternative is to use alwayslink=1 where required.")
    )
    abstract val legacyWholeArchive: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "strip",
        defaultValue = "sometimes",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Specifies whether to strip binaries and shared libraries "
                + " (using \"-Wl,--strip-debug\").  The default value of 'sometimes'"
                + " means strip iff --compilation_mode=fastbuild."),
        converter = StripModeConverter::class
    )
    abstract val stripBinaries: StripMode?

    @get:com.google.devtools.common.options.Option(
        name = "fdo_instrument",
        defaultValue = "null",
        implicitRequirements = ["--copt=-Wno-error"],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Generate binaries with FDO instrumentation. With Clang/LLVM compiler, it also accepts"
                + " the directory name under which the raw profile file(s) will be dumped at"
                + " runtime.")
    )
    abstract val fdoInstrumentForBuild: String?

    @get:com.google.devtools.common.options.Option(
        name = "fdo_optimize",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Use FDO profile information to optimize compilation. Specify the name "
                + "of a zip file containing a .gcda file tree, an afdo file containing "
                + "an auto profile, or an LLVM profile file. This flag also accepts files "
                + "specified as labels (e.g. `//foo/bar:file.afdo` - you may need to add "
                + "an `exports_files` directive to the corresponding package) and labels "
                + "pointing to `fdo_profile` targets. This flag will be superseded by the "
                + "`fdo_profile` rule.")
    )
    abstract val fdoOptimizeForBuild: String?

    val fdoOptimize: String?
        /**
         * Returns the --fdo_optimize value if FDO is specified and active for this configuration, the
         * default value otherwise.
         */
        get() = this.fdoOptimizeForBuild

    @get:com.google.devtools.common.options.Option(
        name = "cs_fdo_instrument",
        defaultValue = "null",
        implicitRequirements = ["--copt=-Wno-error"],
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Generate binaries with context sensitive FDO instrumentation. With Clang/LLVM compiler, "
                + "it also accepts the directory name under which the raw profile file(s) will be "
                + "dumped at runtime.")
    )
    abstract val csFdoInstrumentForBuild: String?

    @get:com.google.devtools.common.options.Option(
        name = "cs_fdo_absolute_path",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Use CSFDO profile information to optimize compilation. Specify the absolute path name "
                + "of the zip file containing the profile file, a raw or an indexed "
                + "LLVM profile file.")
    )
    abstract val csFdoAbsolutePathForBuild: String?

    @get:com.google.devtools.common.options.Option(
        name = "xbinary_fdo",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        converter = EmptyToNullLabelConverter::class,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Use XbinaryFDO profile information to optimize compilation. Specify the name "
                + "of default cross binary profile. When the option is used together with "
                + "--fdo_instrument/--fdo_optimize/--fdo_profile, those options will always "
                + "prevail as if xbinary_fdo is never specified. ")
    )
    abstract val xfdoProfileLabel: Label?

    @get:com.google.devtools.common.options.Option(
        name = "fdo_prefetch_hints",
        defaultValue = "null",
        converter = LabelConverter::class,
        category = "flags",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Use cache prefetch hints."
    )
    abstract val fdoPrefetchHintsLabel: Label?

    val fdoPrefetchHintsLabelValue: Label?
        /** Returns the --fdo_prefetch_hints value.  */
        get() = this.fdoPrefetchHintsLabel

    @get:com.google.devtools.common.options.Option(
        name = "fdo_profile",
        defaultValue = "null",
        category = "flags",
        converter = EmptyToNullLabelConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "The fdo_profile representing the profile to be used for optimization."
    )
    abstract val fdoProfileLabel: Label?

    @get:com.google.devtools.common.options.Option(
        name = "cs_fdo_profile",
        defaultValue = "null",
        category = "flags",
        converter = LabelConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("The cs_fdo_profile representing the context sensitive profile to be used for"
                + " optimization.")
    )
    abstract val csFdoProfileLabel: Label?

    @get:com.google.devtools.common.options.Option(
        name = "enable_remaining_fdo_absolute_paths",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "If set, any use of absolute paths for FDO will raise an error."
    )
    abstract val enableFdoProfileAbsolutePath: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "enable_propeller_optimize_absolute_paths",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "If set, any use of absolute paths for propeller optimize will raise an error."
    )
    abstract val enablePropellerOptimizeAbsolutePath: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "propeller_optimize_absolute_cc_profile",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Absolute path name of cc_profile file for Propeller Optimized builds.",
        deprecationWarning = "Deprecated. Use --propeller_optimize instead."
    )
    abstract val propellerOptimizeAbsoluteCCProfile: String?

    @get:com.google.devtools.common.options.Option(
        name = "propeller_optimize_absolute_ld_profile",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Absolute path name of ld_profile file for Propeller Optimized builds.",
        deprecationWarning = "Deprecated. Use --propeller_optimize instead."
    )
    abstract val propellerOptimizeAbsoluteLdProfile: String?

    @get:com.google.devtools.common.options.Option(
        name = "propeller_optimize",
        defaultValue = "null",
        converter = LabelConverter::class,
        category = "flags",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Use Propeller profile information to optimize the build target."
                + "A propeller profile must consist of at least one of two files, a cc profile "
                + "and a ld profile.  This flag accepts a build label which must refer to "
                + "the propeller profile input files. For example, the BUILD file that "
                + "defines the label, in a/b/BUILD:"
                + "propeller_optimize("
                + "    name = \"propeller_profile\","
                + "    cc_profile = \"propeller_cc_profile.txt\","
                + "    ld_profile = \"propeller_ld_profile.txt\","
                + ")"
                + "An exports_files directive may have to be added to the corresponding package "
                + "to make these files visible to Bazel. The option must be used as: "
                + "--propeller_optimize=//a/b:propeller_profile")
    )
    abstract val propellerOptimizeLabel: Label?

    val propellerOptimizeLabelValue: Label?
        get() = this.propellerOptimizeLabel

    @get:com.google.devtools.common.options.Option(
        name = "memprof_profile",
        defaultValue = "null",
        converter = LabelConverter::class,
        category = "flags",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Use memprof profile."
    )
    abstract val memProfProfileLabel: Label?

    val memProfProfileLabelValue: Label?
        /** Returns the --memprof_profile value.  */
        get() = this.memProfProfileLabel

    @get:com.google.devtools.common.options.Option(
        name = "proto_profile",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        defaultValue = "true",
        help = "Whether to pass profile_path to the proto compiler."
    )
    abstract val protoProfile: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "proto_profile_path",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        defaultValue = "null",
        converter = LabelConverter::class,
        help = ("The profile to pass to the proto compiler as profile_path. If unset, but "
                + " --proto_profile is true (the default), infers the path from --fdo_optimize.")
    )
    abstract val protoProfilePath: Label?

    @get:com.google.devtools.common.options.Option(
        name = "save_temps",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If set, temporary outputs from gcc will be saved.  "
                + "These include .s files (assembler code), .i files (preprocessed C) and "
                + ".ii files (preprocessed C++).")
    )
    abstract val saveTemps: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "per_file_copt",
        allowMultiple = true,
        converter = PerLabelOptions.PerLabelOptionsConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Additional options to selectively pass to gcc when compiling certain files. "
                + "This option can be passed multiple times. "
                + "Syntax: regex_filter@option_1,option_2,...,option_n. Where regex_filter stands "
                + "for a list of include and exclude regular expression patterns (Also see "
                + "--instrumentation_filter). option_1 to option_n stand for "
                + "arbitrary command line options. If an option contains a comma it has to be "
                + "quoted with a backslash. Options can contain @. Only the first @ is used to "
                + "split the string. Example: "
                + "--per_file_copt=//foo/.*\\.cc,-//foo/bar\\.cc@-O0 adds the -O0 "
                + "command line option to the gcc command line of all cc files in //foo/ "
                + "except bar.cc.")
    )
    abstract val perFileCopts: MutableList<PerLabelOptions>?

    @get:com.google.devtools.common.options.Option(
        name = "per_file_ltobackendopt",
        allowMultiple = true,
        converter = PerLabelOptions.PerLabelOptionsConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Additional options to selectively pass to LTO backend (under --features=thin_lto) when "
                + "compiling certain backend objects. This option can be passed multiple times. "
                + "Syntax: regex_filter@option_1,option_2,...,option_n. Where regex_filter stands "
                + "for a list of include and exclude regular expression patterns. "
                + "option_1 to option_n stand for arbitrary command line options. "
                + "If an option contains a comma it has to be quoted with a backslash. "
                + "Options can contain @. Only the first @ is used to split the string. Example: "
                + "--per_file_ltobackendopt=//foo/.*\\.o,-//foo/bar\\.o@-O0 adds the -O0 "
                + "command line option to the LTO backend command line of all o files in //foo/ "
                + "except bar.o.")
    )
    abstract val perFileLtoBackendOpts: MutableList<PerLabelOptions>?

    @get:com.google.devtools.common.options.Option(
        name = "host_copt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Additional options to pass to the C compiler for tools built in the exec"
                + " configurations.")
    )
    abstract val hostCoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "host_cxxopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Additional options to pass to C++ compiler for tools built in the exec"
                + " configurations.")
    )
    abstract val hostCxxoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "host_conlyopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Additional option to pass to the C compiler when compiling C (but not C++) source files"
                + " in the exec configurations.")
    )
    abstract val hostConlyoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "host_per_file_copt",
        allowMultiple = true,
        converter = PerLabelOptions.PerLabelOptionsConverter::class,
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("Additional options to selectively pass to the C/C++ compiler when "
                + "compiling certain files in the exec configurations. "
                + "This option can be passed multiple times. "
                + "Syntax: regex_filter@option_1,option_2,...,option_n. Where regex_filter stands "
                + "for a list of include and exclude regular expression patterns (Also see "
                + "--instrumentation_filter). option_1 to option_n stand for "
                + "arbitrary command line options. If an option contains a comma it has to be "
                + "quoted with a backslash. Options can contain @. Only the first @ is used to "
                + "split the string. Example: "
                + "--host_per_file_copt=//foo/.*\\.cc,-//foo/bar\\.cc@-O0 adds the -O0 "
                + "command line option to the gcc command line of all cc files in //foo/ "
                + "except bar.cc.")
    )
    abstract val hostPerFileCoptsList: MutableList<PerLabelOptions>?

    @get:com.google.devtools.common.options.Option(
        name = "host_linkopt",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Additional option to pass to linker when linking tools in the exec" + " configurations."
    )
    abstract val hostLinkoptList: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "grte_top",
        defaultValue = "null",
        converter = LibcTopLabelConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("A label to a checked-in libc library. The default value is selected by the crosstool "
                + "toolchain, and you almost never need to override it.")
    )
    abstract val libcTopLabel: Label?

    @get:com.google.devtools.common.options.Option(
        name = "host_grte_top",
        defaultValue = "null",
        converter = LibcTopLabelConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If specified, this setting overrides the libc top-level directory (--grte_top) "
                + "for the exec configuration.")
    )
    abstract val hostLibcTopLabel: Label?

    @get:com.google.devtools.common.options.Option(
        name = "experimental_inmemory_dotd_files",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If enabled, C++ .d files will be passed through in memory directly from the remote "
                + "build nodes instead of being written to disk.")
    )
    abstract val inmemoryDotdFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_omitfp",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If true, use libunwind for stack unwinding, and compile with "
                + "-fomit-frame-pointer and -fasynchronous-unwind-tables.")
    )
    abstract val experimentalOmitfp: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "share_native_deps",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If true, native libraries that contain identical functionality "
                + "will be shared among different targets")
    )
    abstract val shareNativeDeps: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "strict_system_includes",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("If true, headers found through system include paths (-isystem) are also required to be "
                + "declared.")
    )
    abstract val strictSystemIncludes: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_use_llvm_covmap",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If specified, Bazel will generate llvm-cov coverage map information rather than "
                + "gcov when collect_code_coverage is enabled.")
    )
    abstract val useLLVMCoverageMapFormat: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_dont_enable_host_nonhost_crosstool_features",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If true, Bazel will not enable 'host' and 'nonhost' features in the c++ toolchain "
                + "(see https://github.com/bazelbuild/bazel/issues/7407 for more information).")
    )
    abstract val dontEnableHostNonhost: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_make_thinlto_command_lines_standalone",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
        help = "This flag is a noop and scheduled for removal."
    )
    @get:Deprecated("")
    abstract val useStandaloneLtoIndexingCommandLines: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_require_ctx_in_configure_features",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
        help = "This flag is a noop and scheduled for removal."
    )
    @get:Deprecated("")
    abstract val requireCtxInConfigureFeatures: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_validate_top_level_header_inclusions",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
        help = "This flag is a noop and scheduled for removal."
    )
    @get:Deprecated("")
    abstract val validateTopLevelHeaderInclusions: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_remove_legacy_whole_archive",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("If true, Bazel will not link library dependencies as whole archive by default "
                + "(see https://github.com/bazelbuild/bazel/issues/7362 for migration instructions).")
    )
    abstract val removeLegacyWholeArchive: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_disable_legacy_cc_provider",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "No-op flag. Will be removed in a future release."
    )
    abstract val disableLegacyCcProvider: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_enable_cc_toolchain_resolution",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "No-op flag. Will be removed in a future release."
    )
    abstract val enableCcToolchainResolutionNoOp: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_save_feature_state",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = "Save the state of enabled and requested feautres as an output of compilation."
    )
    abstract val saveFeatureState: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_use_specific_tool_files",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("Use cc toolchain's compiler_files, as_files, and ar_files as inputs to appropriate "
                + "actions. See https://github.com/bazelbuild/bazel/issues/8531")
    )
    abstract val useSpecificToolFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_disable_nocopts",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = ("When enabled, it removes nocopts attribute from C++ rules. See"
                + " https://github.com/bazelbuild/bazel/issues/8706 for details.")
    )
    abstract val disableNoCopts: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "apple_generate_dsym",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES],
        help = "Whether to generate debug symbol(.dSYM) file(s)."
    )
    abstract val appleGenerateDsym: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "objc_generate_linkmap",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = "Specifies whether to generate a linkmap file."
    )
    abstract val objcGenerateLinkmap: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "objc_enable_binary_stripping",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.ACTION_COMMAND_LINES],
        help = ("Whether to perform symbol and dead-code strippings on linked binaries. Binary "
                + "strippings will be performed if both this flag and --compilation_mode=opt are "
                + "specified.")
    )
    abstract val objcEnableBinaryStripping: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_generate_llvm_lcov",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = "If true, coverage for clang will generate an LCOV report."
    )
    abstract val generateLlvmLcov: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_use_cpp_compile_header_mnemonic",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "If enabled, give distinguishing mnemonic to header processing actions"
    )
    abstract val useCppCompileHeaderMnemonic: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_use_cpp_compile_action_args_params_file",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL
        ],
        help = "If enabled, write CppCompileAction exposed action.args to parameters file."
    )
    abstract val useArgsParamsFile: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_unsupported_and_brittle_include_scanning",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("Whether to narrow inputs to C/C++ compilation by parsing #include lines from input"
                + " files. This can improve performance and incrementality by decreasing the size of"
                + " compilation input trees. However, it can also break builds because the include"
                + " scanner does not fully implement C preprocessor semantics. In particular, it does"
                + " not understand dynamic #include directives and ignores preprocessor conditional"
                + " logic. Use at your own risk. Any issues relating to this flag that are filed will"
                + " be closed."
                + " At Google without this flag your build will most likely fail.")
    )
    abstract val includeScanning: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "cc_include_scanning",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("Whether to narrow inputs to C/C++ compilation by parsing #include lines from input"
                + " files. This can improve performance and incrementality by decreasing the size of"
                + " compilation input trees. However, it can also break builds because the include"
                + " scanner does not fully implement C preprocessor semantics. In particular, it does"
                + " not understand dynamic #include directives and ignores preprocessor conditional"
                + " logic. Use at your own risk. Any issues relating to this flag that are filed will"
                + " be closed."
                + " At Google without this flag your build will most likely fail.")
    )
    abstract val includeScanningInternal: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "cc_dotd_files",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS
        ],
        defaultValue = "true",
        help = "Whether to generate and analyze .d files."
    )
    abstract val generateDotdFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "objc_use_dotd_pruning",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("If set, .d files emitted by clang will be used to prune the set of inputs passed into "
                + "objc compiles.")
    )
    abstract val objcGenerateDotdFiles: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_cc_implementation_deps",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = "If enabled, cc_library targets can use attribute `implementation_deps`."
    )
    abstract val experimentalCcImplementationDeps: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_cpp_modules",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS, com.google.devtools.common.options.OptionEffectTag.EXECUTION, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("Enables experimental C++20 modules support. Use it with `module_interfaces` attribute on"
                + " `cc_binary` and `cc_library`. While the support is behind the experimental flag,"
                + " there are no guarantees about incompatible changes to it or even keeping the"
                + " support in the future. Consider those risks when using it.")
    )
    abstract val experimentalCppModules: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_link_static_libraries_once",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE, com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL
        ],
        help = ("If enabled, cc_shared_library will link all libraries statically linked into it, that"
                + " should only be linked once.")
    )
    abstract val experimentalLinkStaticLibrariesOnce: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "experimental_cpp_compile_resource_estimation",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.EXPERIMENTAL],
        help = ("If enabled, will estimate precise resource usage for local execution of"
                + " CppCompileAction.")
    )
    abstract val experimentalCppCompileResourcesEstimation: Boolean
}
