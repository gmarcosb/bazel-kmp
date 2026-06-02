// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import java.util.Locale

// LINT.IfChange
/**
 * A category of artifacts that are candidate input/output to an action, for which the toolchain can
 * select a single artifact.
 */
enum class ArtifactCategory(
    val defaultPrefix: String?,
    defaultExtension: String,
    vararg extraAllowedExtensions: String?
) {
    STATIC_LIBRARY("lib", ".a", ".lib"),
    ALWAYSLINK_STATIC_LIBRARY("lib", ".lo", ".lo.lib"),
    DYNAMIC_LIBRARY("lib", ".so", ".dylib", ".dll", ".pyd", ".wasm"),
    EXECUTABLE("", "", ".exe", ".wasm"),
    INTERFACE_LIBRARY("lib", ".ifso", ".tbd", ".if.lib", ".lib"),
    PIC_FILE("", ".pic"),
    INCLUDED_FILE_LIST("", ".d"),
    SERIALIZED_DIAGNOSTICS_FILE("", ".dia"),
    OBJECT_FILE("", ".o", ".obj"),
    PIC_OBJECT_FILE("", ".pic.o"),
    CPP_MODULE("", ".pcm", ".gcm", ".ifc"),
    CPP_MODULES_INFO("", ".CXXModules.json"),
    CPP_MODULES_DDI("", ".ddi"),
    CPP_MODULES_MODMAP("", ".modmap"),
    CPP_MODULES_MODMAP_INPUT("", ".modmap.input"),
    GENERATED_ASSEMBLY("", ".s", ".asm"),
    PROCESSED_HEADER("", ".processed"),
    GENERATED_HEADER("", ".h"),
    PREPROCESSED_C_SOURCE("", ".i"),
    PREPROCESSED_CPP_SOURCE("", ".ii"),
    COVERAGE_DATA_FILE("", ".gcno"),

    // A matched-clif protobuf. Typically in binary format, but could be text depending on
    // the options passed to the clif_matcher.
    CLIF_OUTPUT_PROTO("", ".opb");

    val defaultExtension: String?

    // The extensions allowed for this artifact name pattern, Bazel should recognized them as
    // corresponding file type in CppFileTypes.java
    val allowedExtensions: com.google.common.collect.ImmutableList<String?>

    init {
        this.defaultExtension = defaultExtension
        this.allowedExtensions =
            com.google.common.collect.ImmutableList.Builder<String?>()
                .add(defaultExtension)
                .add(*extraAllowedExtensions)
                .build()
    }

    val categoryName: String?
        /** Returns the name of the category.  */
        get() = this.toString().lowercase(Locale.getDefault())

    fun getAllowedExtensions(): com.google.common.collect.ImmutableList<String?> {
        return allowedExtensions
    }
} // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/cc_helper_internal.bzl)
