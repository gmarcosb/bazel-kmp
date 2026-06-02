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
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp

/** Class holding constants for all C++ action names  */
object CppActionNames {
    /** A string constant used to compute CC_FLAGS make variable value  */
    const val CC_FLAGS_MAKE_VARIABLE: String = "cc-flags-make-variable"

    /** A string constant for the strip action name.  */
    const val STRIP: String = "strip"

    /** A string constant for the object copy action name.  */
    const val OBJ_COPY: String = "objcopy_embed_data"

    /** A string constant for the linkstamp-compile action.  */
    const val LINKSTAMP_COMPILE: String = "linkstamp-compile"

    /** A string constant for the c compilation action.  */
    const val C_COMPILE: String = "c-compile"

    /** A string constant for the c++ compilation action.  */
    const val CPP_COMPILE: String = "c++-compile"

    /** A string constant for the c++ module compile action.  */
    const val CPP_MODULE_CODEGEN: String = "c++-module-codegen"

    /** A string constant for the objc compilation action.  */
    const val OBJC_COMPILE: String = "objc-compile"

    /** A string constant for the objc++ compile action.  */
    const val OBJCPP_COMPILE: String = "objc++-compile"

    /** A string constant for the c++ header parsing.  */
    const val CPP_HEADER_PARSING: String = "c++-header-parsing"

    /** A string constant for the c++20 modules deps scanning  */
    const val CPP_MODULE_DEPS_SCANNING: String = "c++-module-deps-scanning"

    /** A string constant for the c++20 module compile action.  */
    const val CPP20_MODULE_COMPILE: String = "c++20-module-compile"

    const val CPP20_MODULE_CODEGEN: String = "c++20-module-codegen"

    /**
     * A string constant for the c++ module compilation action. Note: currently we don't support C
     * module compilation.
     */
    const val CPP_MODULE_COMPILE: String = "c++-module-compile"

    /** A string constant for the assembler actions.  */
    const val ASSEMBLE: String = "assemble"

    const val PREPROCESS_ASSEMBLE: String = "preprocess-assemble"

    /**
     * A string constant for the clif actions. Bazel enables different features of the toolchain based
     * on the name of the action. This name enables the clif_matcher feature, which switches the
     * "compiler" to the clif_matcher and adds some additional arguments as described in the CROSSTOOL
     * file.
     */
    const val CLIF_MATCH: String = "clif-match"

    /** Name of the action producing static library.  */
    const val CPP_LINK_STATIC_LIBRARY: String = "c++-link-static-library"

    /** Name of the action producing dynamic library from cc_library.  */
    const val CPP_LINK_NODEPS_DYNAMIC_LIBRARY: String = "c++-link-nodeps-dynamic-library"

    /** Name of the action producing dynamic library from cc_binary.  */
    const val CPP_LINK_DYNAMIC_LIBRARY: String = "c++-link-dynamic-library"

    /** Name of the action producing executable binary.  */
    const val CPP_LINK_EXECUTABLE: String = "c++-link-executable"

    /** Name of the objc action producing dynamic library  */
    const val OBJC_FULLY_LINK: String = "objc-fully-link"

    /** Name of the objc action producing objc executable binary  */
    const val OBJC_EXECUTABLE: String = "objc-executable"

    const val LTO_INDEXING: String = "lto-indexing"

    /** Name of the action producing thinlto index for dynamic library.  */
    const val LTO_INDEX_DYNAMIC_LIBRARY: String = "lto-index-for-dynamic-library"

    /** Name of the action producing thinlto index for nodeps dynamic library.  */
    const val LTO_INDEX_NODEPS_DYNAMIC_LIBRARY: String = "lto-index-for-nodeps-dynamic-library"

    /** Name of the action producing thinlto index for executable binary.  */
    const val LTO_INDEX_EXECUTABLE: String = "lto-index-for-executable"

    const val LTO_BACKEND: String = "lto-backend"

    const val CPP_HEADER_ANALYSIS: String = "c++-header-analysis"
}
