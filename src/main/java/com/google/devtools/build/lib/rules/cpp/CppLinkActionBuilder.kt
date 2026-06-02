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
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/** Builder class to construct C++ link action.  */
object CppLinkActionBuilder {
    fun newActionConstruction(
        context: ActionConstructionContext,
        config: BuildConfigurationValue,
        shareableArtifacts: Boolean
    ): LinkActionConstruction {
        return LinkActionConstruction(context, config, shareableArtifacts)
    }

    /**
     * Provides ActionConstructionContext, BuildConfigurationValue and methods for creating
     * intermediate and output artifacts for C++ linking.
     * 
     * 
     * This is unfortunately necessary, because most of the time, these artifacts are well-behaved
     * ones sitting under a package directory, but nativedeps link actions can be shared. In order to
     * avoid creating every artifact here with `getShareableArtifact()`, we abstract the
     * artifact creation away.
     * 
     * 
     * With shareableArtifacts set to true the implementation can create artifacts anywhere.
     * 
     * 
     * Necessary when the LTO backend actions of libraries should be shareable, and thus cannot be
     * under the package directory.
     * 
     * 
     * Necessary because the actions of nativedeps libraries should be shareable, and thus cannot
     * be under the package directory.
     */
    class LinkActionConstruction internal constructor(
        context: ActionConstructionContext,
        config: BuildConfigurationValue,
        shareableArtifacts: Boolean
    ) {
        private val shareableArtifacts: Boolean
        private val context: ActionConstructionContext
        private val config: BuildConfigurationValue

        fun getContext(): ActionConstructionContext {
            return context
        }

        fun getConfig(): BuildConfigurationValue {
            return config
        }

        init {
            this.context = context
            this.config = config
            this.shareableArtifacts = shareableArtifacts
        }

        fun create(rootRelativePath: PathFragment?): Artifact {
            val repositoryName: RepositoryName? = context.getActionOwner().getLabel().getRepository()
            if (shareableArtifacts) {
                return context.getShareableArtifact(
                    rootRelativePath, config.getBinDirectory(repositoryName)
                )
            } else {
                return context.getDerivedArtifact(rootRelativePath, config.getBinDirectory(repositoryName))
            }
        }

        fun createTreeArtifact(rootRelativePath: PathFragment?): SpecialArtifact {
            val repositoryName: RepositoryName? = context.getActionOwner().getLabel().getRepository()
            if (shareableArtifacts) {
                return context
                    .getAnalysisEnvironment()
                    .getTreeArtifact(rootRelativePath, config.getBinDirectory(repositoryName))
            } else {
                return context.getTreeArtifact(rootRelativePath, config.getBinDirectory(repositoryName))
            }
        }

        val binDirectory: ArtifactRoot
            get() = config.getBinDirectory(context.getActionOwner().getLabel().getRepository())
    }
}
