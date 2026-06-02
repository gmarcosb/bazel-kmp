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

/** Rule class definitions for C++ rules.  */
object CppRuleClasses {
    /** A string constant for the Objective-C language feature.  */
    const val LANG_OBJC: String = "lang_objc"

    /** Name of the feature that will be exempt from flag filtering when nocopts are used  */
    const val UNFILTERED_COMPILE_FLAGS_FEATURE_NAME: String = "unfiltered_compile_flags"

    /** A string constant for the parse_headers feature.  */
    const val PARSE_HEADERS: String = "parse_headers"

    /**
     * A string constant for the module_maps feature; this is a precondition to the layering_check and
     * header_modules features.
     */
    const val MODULE_MAPS: String = "module_maps"

    /** A string constant for the cpp_modules feature.  */
    const val CPP_MODULES: String = "cpp_modules"

    /**
     * A string constant for the serialized_diagnostics_file feature. This feature generates the .dia
     * file.
     */
    const val SERIALIZED_DIAGNOSTICS_FILE: String = "serialized_diagnostics_file"

    /** A string constant for the module_map_home_cwd feature.  */
    const val MODULE_MAP_HOME_CWD: String = "module_map_home_cwd"

    /**
     * A string constant for the module_map_without_extern_module feature.
     * 
     * 
     * This features is a transitional feature; enabling it means that generated module maps will
     * not have "extern module" declarations inside them; instead, the module maps need to be passed
     * via the dependent_module_map_files build variable.
     * 
     * 
     * This variable is phrased negatively to aid the roll-out: currently, the default is that
     * "extern module" declarations are generated.
     */
    const val MODULE_MAP_WITHOUT_EXTERN_MODULE: String = "module_map_without_extern_module"

    /** A string constant for the layering_check feature.  */
    const val LAYERING_CHECK: String = "layering_check"

    /**
     * A string constant for the use_header_modules feature.
     * 
     * 
     * This feature is only used during rollout; we expect to default enable this once we have
     * verified that module-enabled compilation is stable enough.
     */
    const val USE_HEADER_MODULES: String = "use_header_modules"

    /**
     * A string constant for the generate_submodules feature.
     * 
     * 
     * This feature is only used temporarily to make the switch to using submodules easier. With
     * submodules, each header of a cc_library is placed into a submodule of the module generated for
     * the appropriate target. As this influences the layering_check semantics and needs to be synced
     * with a clang release, we want to be able to switch back and forth easily.
     */
    const val GENERATE_SUBMODULES: String = "generate_submodules"


    /**
     * A string constant for the no_legacy_features feature.
     * 
     * 
     * If this feature is enabled, Bazel will not extend the crosstool configuration with the
     * default legacy feature set.
     */
    const val NO_LEGACY_FEATURES: String = "no_legacy_features"

    /** A string constant for the feature that makes us build per-object debug info files.  */
    const val PER_OBJECT_DEBUG_INFO: String = "per_object_debug_info"

    /**
     * A string constant for the PIC feature.
     * 
     * 
     * If this feature is active (currently it cannot be switched off) and PIC compilation is
     * requested, the "pic" build variable will be defined with an empty string as its value.
     */
    const val PIC: String = "pic"

    /** A string constant for a feature that indicates that the toolchain can produce PIC objects.  */
    const val SUPPORTS_PIC: String = "supports_pic"

    /**
     * A string constant for a feature that indicates that PIC compiles are preferred for binaries
     * even in optimized builds. For configurations that use dynamic linking for tests, this provides
     * increases sharing of artifacts between tests and binaries at the cost of performance overhead.
     */
    const val PREFER_PIC_FOR_OPT_BINARIES: String = "prefer_pic_for_opt_binaries"

    /** A string constant for the feature the represents preprocessor defines.  */
    const val PREPROCESSOR_DEFINES: String = "preprocessor_defines"

    /** A string constant for the includes feature.  */
    const val INCLUDES: String = "includes"

    /** A string constant for the include_paths feature.  */
    const val INCLUDE_PATHS: String = "include_paths"

    /** A string constant for the external_include_paths feature.  */
    const val EXTERNAL_INCLUDE_PATHS: String = "external_include_paths"

    /** A string constant for the feature signalling static linking mode.  */
    const val STATIC_LINKING_MODE: String = "static_linking_mode"

    /** A string constant for the feature signalling dynamic linking mode.  */
    const val DYNAMIC_LINKING_MODE: String = "dynamic_linking_mode"

    /** A string constant for the ThinLTO feature.  */
    const val THIN_LTO: String = "thin_lto"

    /** A string constant for the LTO indexing bitcode feature.  */
    const val NO_USE_LTO_INDEXING_BITCODE_FILE: String = "no_use_lto_indexing_bitcode_file"

    /** A string constant for the LTO separate native object directory feature.  */
    const val USE_LTO_NATIVE_OBJECT_DIRECTORY: String = "use_lto_native_object_directory"

    /*
   * A string constant for allowing implicit ThinLTO enablement for AFDO.
   */
    const val AUTOFDO_IMPLICIT_THINLTO: String = "autofdo_implicit_thinlto"

    /*
   * A string constant for enabling ThinLTO for AFDO implicitly.
   */
    const val ENABLE_AFDO_THINLTO: String = "enable_afdo_thinlto"

    /*
   * A string constant for enabling ThinLTO for FDO implicitly.
   */
    const val ENABLE_FDO_THINLTO: String = "enable_fdo_thinlto"

    /*
   * A string constant for enabling ThinLTO for XFDO implicitly.
   */
    const val ENABLE_XFDO_THINLTO: String = "enable_xbinaryfdo_thinlto"

    /** A string constant for the split functions feature.  */
    const val SPLIT_FUNCTIONS: String = "split_functions"

    /** A string constant for enabling split functions for FDO implicitly.  */
    const val ENABLE_FDO_SPLIT_FUNCTIONS: String = "enable_fdo_split_functions"

    /** A string constant for the fsafdo feature.  */
    const val FSAFDO: String = "fsafdo"

    /** A string constant for enabling fsafdo for AutoFDO implicitly.  */
    const val ENABLE_FSAFDO: String = "enable_fsafdo"

    /** A string constant for enabling memprof_optimize for FDO implicitly.  */
    const val ENABLE_FDO_MEMPROF_OPTIMIZE: String = "enable_fdo_memprof_optimize"

    /** A string constant for allowing memprof_optimize for FDO implicitly.  */
    const val FDO_IMPLICIT_MEMPROF_OPTIMIZE: String = "fdo_implicit_memprof_optimize"

    /** A string constant for enabling memprof_optimize for AutoFDO implicitly.  */
    const val ENABLE_AUTOFDO_MEMPROF_OPTIMIZE: String = "enable_autofdo_memprof_optimize"

    /** A string constant for allowing memprof_optimize for AutoFDO implicitly.  */
    const val AUTOFDO_IMPLICIT_MEMPROF_OPTIMIZE: String = "autofdo_implicit_memprof_optimize"

    /**
     * A string constant for allowing use of shared LTO backend actions for linkstatic tests building
     * with ThinLTO.
     */
    const val THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS: String =
        "thin_lto_linkstatic_tests_use_shared_nonlto_backends"

    /**
     * A string constant for allowing use of shared LTO backend actions for all linkstatic links
     * building with ThinLTO.
     */
    const val THIN_LTO_ALL_LINKSTATIC_USE_SHARED_NONLTO_BACKENDS: String =
        "thin_lto_all_linkstatic_use_shared_nonlto_backends"

    /** A string constant for native deps links.  */
    const val NATIVE_DEPS_LINK: String = "native_deps_link"

    /** A string constant for java launcher links.  */
    const val JAVA_LAUNCHER_LINK: String = "java_launcher_link"

    /** A string constant for python launcher links.  */
    const val PY_LAUNCHER_LINK: String = "py_launcher_link"

    /**
     * A string constant for the PDB file generation feature, should only be used for toolchains
     * targeting Windows that include a linker producing PDB files
     */
    const val GENERATE_PDB_FILE: String = "generate_pdb_file"

    /** A string constant for a feature to copy dynamic libraries to the binary's directory.  */
    const val COPY_DYNAMIC_LIBRARIES_TO_BINARY: String = "copy_dynamic_libraries_to_binary"

    /** A string constant for a feature to statically link the C++ runtimes.  */
    const val STATIC_LINK_CPP_RUNTIMES: String = "static_link_cpp_runtimes"

    /**
     * A string constant for a feature that indicates we are using a toolchain building for Windows.
     */
    const val TARGETS_WINDOWS: String = "targets_windows"

    /**
     * A string constant for a feature that indicates we are using a toolchain building for Windows.
     */
    const val SUPPORTS_INTERFACE_SHARED_LIBRARIES: String = "supports_interface_shared_libraries"

    /** A string constant for /showIncludes parsing feature, should only be used for MSVC toolchain  */
    const val PARSE_SHOWINCLUDES: String = "parse_showincludes"

    /** A string constant for a feature that, if enabled, disables .d file handling.  */
    const val NO_DOTD_FILE: String = "no_dotd_file"

    /**
     * A string constant for a feature that, if enabled, shortens the virtual include paths via
     * hashing.
     */
    const val SHORTEN_VIRTUAL_INCLUDES: String = "shorten_virtual_includes"

    /*
   * A string constant for the fdo_instrument feature.
   */
    const val FDO_INSTRUMENT: String = "fdo_instrument"

    /** A string constant for the cs_fdo_instrument feature.  */
    const val CS_FDO_INSTRUMENT: String = "cs_fdo_instrument"

    /** A string constant for the fdo_optimize feature.  */
    const val FDO_OPTIMIZE: String = "fdo_optimize"

    /** A string constant for the cs_fdo_optimize feature.  */
    const val CS_FDO_OPTIMIZE: String = "cs_fdo_optimize"

    /** A string constant for the cache prefetch hints feature.  */
    const val FDO_PREFETCH_HINTS: String = "fdo_prefetch_hints"

    /** A string constant for the propeller optimize feature.  */
    const val PROPELLER_OPTIMIZE: String = "propeller_optimize"

    /** A string constant for the memprof profile optimization feature.  */
    const val MEMPROF_OPTIMIZE: String = "memprof_optimize"

    /** A string constant for the autofdo feature.  */
    const val AUTOFDO: String = "autofdo"


    /** A string constant for the xbinaryfdo feature.  */
    const val XBINARYFDO: String = "xbinaryfdo"

    /** A string constant for the coverage feature.  */
    const val COVERAGE: String = "coverage"

    /** Produce artifacts for coverage in llvm coverage mapping format.  */
    const val LLVM_COVERAGE_MAP_FORMAT: String = "llvm_coverage_map_format"

    /** Produce artifacts for coverage in gcc coverage mapping format.  */
    const val GCC_COVERAGE_MAP_FORMAT: String = "gcc_coverage_map_format"

    /** A feature marking that the toolchain can use --start-lib/--end-lib flags  */
    const val SUPPORTS_START_END_LIB: String = "supports_start_end_lib"

    /**
     * A feature marking that the toolchain can produce binaries that load shared libraries at
     * runtime.
     */
    const val SUPPORTS_DYNAMIC_LINKER: String = "supports_dynamic_linker"


    const val COMPILER_PARAM_FILE: String = "compiler_param_file"
    const val COMPILER_PARAM_FILE_ON_DEMAND: String = "compiler_param_file_on_demand"

    /**
     * A feature to control whether to use param files for archiving commands. This can be applied to
     * individual targets.
     */
    const val ARCHIVE_PARAM_FILE: String = "archive_param_file"

    /** A feature to use gcc quoting for linking param files.  */
    const val GCC_QUOTING_FOR_PARAM_FILES: String = "gcc_quoting_for_param_files"

    /**
     * A feature to indicate that this target generates debug symbols for a dSYM file. For Apple
     * platform only.
     */
    const val GENERATE_DSYM_FILE_FEATURE_NAME: String = "generate_dsym_file"

    /**
     * A feature to indicate that this target does not generate debug symbols. For Apple platform
     * only.
     * 
     * 
     * Note that the crosstool does not support feature negation in FlagSet.with_feature, which is
     * the mechanism used to condition linker arguments here. Therefore, we expose
     * "no_generate_debug_symbols" in addition to "generate_dsym_file"
     */
    const val NO_GENERATE_DEBUG_SYMBOLS_FEATURE_NAME: String = "no_generate_debug_symbols"

    /** A feature to indicate whether to generate linkmap.  */
    const val GENERATE_LINKMAP_FEATURE_NAME: String = "generate_linkmap"

    /** A feature to indicate whether to do linker deadstrip. For Apple platform only.  */
    const val DEAD_STRIP_FEATURE_NAME: String = "dead_strip"

    /** Name of the exec group that Cpp link actions run under  */
    @com.google.common.annotations.VisibleForTesting
    const val CPP_LINK_EXEC_GROUP: String = "cpp_link"
}
