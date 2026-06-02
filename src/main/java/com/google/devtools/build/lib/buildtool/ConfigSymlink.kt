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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.ArtifactRoot

/** Base class for symlinks to output roots. Used only by [OutputDirectoryLinksUtils].  */
internal open class ConfigSymlink(private val suffix: String, private val configToRoot: ConfigPathGetter) :
    SymlinkDefinition {
    internal fun interface ConfigPathGetter {
        fun apply(configuration: BuildConfigurationValue?, repositoryName: RepositoryName?): ArtifactRoot?
    }

    public override fun getLinkName(symlinkPrefix: String?, workspaceBaseName: String?): String {
        return symlinkPrefix + suffix
    }

    public override fun getLinkPaths(
        buildRequestOptions: BuildRequestOptions?,
        targetConfigs: MutableSet<BuildConfigurationValue?>,
        repositoryName: RepositoryName?,
        outputPath: com.google.devtools.build.lib.vfs.Path?,
        execRoot: com.google.devtools.build.lib.vfs.Path?
    ): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.vfs.Path?>? {
        return targetConfigs.stream()
            .map<Any?>(java.util.function.Function { config: BuildConfigurationValue? ->
                configToRoot.apply(
                    config,
                    repositoryName
                ).getRoot().asPath()
            })
            .distinct()
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
    }
}
