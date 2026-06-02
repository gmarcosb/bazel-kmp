// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.buildtool.BuildRequestOptions
import com.google.devtools.build.lib.cmdline.RepositoryName

/** Represents a single kind of convenience symlink (`bazel-bin`, etc.).  */
interface SymlinkDefinition {
    /**
     * Returns the name for this symlink in the workspace.
     * 
     * 
     * Note that this is independent of the target configuration(s) that may help determine the
     * symlink's destination.
     */
    fun getLinkName(symlinkPrefix: String?, workspaceBaseName: String?): String?

    /**
     * Returns a set of candidate destination paths for the symlink.
     * 
     * 
     * The symlink should only be created if there is exactly one candidate. Zero candidates is a
     * no-op, and more than one candidate means a warning about ambiguous symlink destinations should
     * be emitted.
     * 
     * @param buildRequestOptions options that may control which symlinks get created and what they
     * point to.
     * @param targetConfigs the configurations for which symlinks should be created. If these have
     * conflicting requirements, multiple candidates are returned.
     * @param repositoryName the repository name.
     * @param outputPath the output path.
     * @param execRoot the exec root.
     */
    fun getLinkPaths(
        buildRequestOptions: BuildRequestOptions?,
        targetConfigs: MutableSet<BuildConfigurationValue?>?,
        repositoryName: RepositoryName?,
        outputPath: com.google.devtools.build.lib.vfs.Path?,
        execRoot: com.google.devtools.build.lib.vfs.Path?
    ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>?
}
