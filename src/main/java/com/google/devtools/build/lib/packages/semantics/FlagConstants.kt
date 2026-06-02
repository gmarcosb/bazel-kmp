// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages.semantics

/** This file holds hardcoded flag defaults that vary between Bazel and Blaze.  */ // TODO(b/254084490): This file is a temporary hack. Eliminate once we've flipped the incompatible
// flag in Blaze.
internal object FlagConstants {
    const val DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API: String = "true"
    const val DEFAULT_EXPERIMENTAL_RULE_EXTENSION_API_NAME: String = "+experimental_rule_extension_api"


    // Enable annotations, but not actual type checking, with the effect that the parser tolerates
    // arbitrary expressions in annotations for now.
    const val EXPERIMENTAL_STARLARK_TYPE_SYNTAX_FLAG_NAME: String = "+experimental_starlark_type_syntax"
    const val DEFAULT_EXPERIMENTAL_STARLARK_TYPE_SYNTAX: String = "true"
    const val DEFAULT_EXPERIMENTAL_STARLARK_TYPE_CHECKING: String = "false"
    const val DEFAULT_EXPERIMENTAL_STARLARK_TYPES_ALLOWED_PATHS: String = ""

    const val DEFAULT_INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX: String = "true"
    const val DEFAULT_INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX: String = "true"

    const val INCOMPATIBLE_PACKAGE_GROUP_HAS_PUBLIC_SYNTAX: String = "+incompatible_package_group_has_public_syntax"
    const val INCOMPATIBLE_FIX_PACKAGE_GROUP_REPOROOT_SYNTAX: String = "+incompatible_fix_package_group_reporoot_syntax"

    const val DEFAULT_INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION: String = "true"
    const val DEFAULT_INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION_NAME: String =
        "+incompatible_enable_proto_toolchain_resolution"

    const val DEFAULT_INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT: String = "true"
    const val DEFAULT_INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT_NAME: String = "+incompatible_no_implicit_file_export"
}
