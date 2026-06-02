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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.vfs.PathFragment

/** Constants associated with `Label`s  */
object LabelConstants {
    /** The subdirectory under the output base which contains external repositories.  */
    @kotlin.jvm.JvmField
    val EXTERNAL_REPOSITORY_LOCATION: PathFragment? = PathFragment.create("external")

    /**
     * The subdirectory under the output base which contains temporary working directories for module
     * extensions.
     */
    val MODULE_EXTENSION_WORKING_DIRECTORY_LOCATION: PathFragment? = PathFragment.create("modextwd")

    /**
     * The name of the package that contains the targets representing external repositories. Only
     * works if `--experimental_disable_external_package` is not in effect.
     */
    @kotlin.jvm.JvmField
    val EXTERNAL_PACKAGE_NAME: PathFragment? = PathFragment.create("external")

    /**
     * The identifier of the package that contains the targets representing external repositories.
     * Only works if `--experimental_disable_external_package` is not in effect.
     */
    @kotlin.jvm.JvmField
    val EXTERNAL_PACKAGE_IDENTIFIER: PackageIdentifier? =
        PackageIdentifier.Companion.createInMainRepo(EXTERNAL_PACKAGE_NAME)

    @kotlin.jvm.JvmField
    val WORKSPACE_FILE_NAME: PathFragment? = PathFragment.create("WORKSPACE")
    @kotlin.jvm.JvmField
    val WORKSPACE_DOT_BAZEL_FILE_NAME: PathFragment? = PathFragment.create("WORKSPACE.bazel")
    @kotlin.jvm.JvmField
    val MODULE_DOT_BAZEL_FILE_NAME: PathFragment? = PathFragment.create("MODULE.bazel")
    @kotlin.jvm.JvmField
    val REPO_FILE_NAME: PathFragment? = PathFragment.create("REPO.bazel")
    val VENDOR_FILE_NAME: PathFragment? = PathFragment.create("VENDOR.bazel")

    @kotlin.jvm.JvmField
    val MODULE_LOCKFILE_NAME: PathFragment? = PathFragment.create("MODULE.bazel.lock")

    // With this prefix, non-main repositories are symlinked under
    // $output_base/execution_root/__main__/external
    @kotlin.jvm.JvmField
    val EXTERNAL_PATH_PREFIX: PathFragment? = PathFragment.create("external")

    // With this prefix, non-main repositories are sibling symlinks of
    // $output_base/execution_root/__main__
    @kotlin.jvm.JvmField
    val EXPERIMENTAL_EXTERNAL_PATH_PREFIX: PathFragment? = PathFragment.create("..")

    // The relative path from the runfiles workspace root to external repository runfile top
    // directory.
    //
    // As a result, external repository runfiles are symlinked to:
    // $runfiles_root/$workspace_name/../$repo_name/<path>, i.e. $runfiles_root/$repo_name/<path>.
    @kotlin.jvm.JvmField
    val EXTERNAL_RUNFILES_PATH_PREFIX: PathFragment? = PathFragment.create("..")
    const val COMMAND_LINE_OPTION_PREFIX: String = "//command_line_option:"
    val COMMAND_LINE_OPTION_PACKAGE_IDENTIFIER: PackageIdentifier? =
        PackageIdentifier.Companion.createInMainRepo(PathFragment.create("command_line_option"))
}
