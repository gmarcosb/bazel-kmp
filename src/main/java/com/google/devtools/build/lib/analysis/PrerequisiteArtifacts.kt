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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * Contains a sequence of prerequisite artifacts and supplies methods for filtering and reporting
 * errors on those artifacts.
 */
class PrerequisiteArtifacts private constructor(
    ruleContext: RuleContext?,
    attributeName: String?,
    artifacts: com.google.common.collect.ImmutableList<Artifact?>?
) {
    private val ruleContext: RuleContext
    private val attributeName: String
    private val artifacts: com.google.common.collect.ImmutableList<Artifact>

    init {
        this.ruleContext = com.google.common.base.Preconditions.checkNotNull<RuleContext>(ruleContext)
        this.attributeName = com.google.common.base.Preconditions.checkNotNull<String>(attributeName)
        this.artifacts =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Artifact>>(
                artifacts
            )
    }

    /**
     * Returns the artifacts this instance contains in an [ImmutableList].
     */
    fun list(): com.google.common.collect.ImmutableList<Artifact> {
        return artifacts
    }

    private fun filter(
        fileType: com.google.common.base.Predicate<String?>,
        errorsForNonMatching: Boolean
    ): PrerequisiteArtifacts {
        val filtered: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.Builder<Artifact?>()

        for (artifact in artifacts) {
            if (fileType.apply(artifact.getFilename())) {
                filtered.add(artifact)
            } else if (errorsForNonMatching) {
                ruleContext.attributeError(
                    attributeName,
                    java.lang.String.format("%s does not match expected type: %s", artifact, fileType)
                )
            }
        }

        return PrerequisiteArtifacts(ruleContext, attributeName, filtered.build())
    }

    /** Returns an equivalent instance but only containing artifacts of the given type.  */
    fun filter(fileType: FileType): PrerequisiteArtifacts {
        return filter(fileType,  /*errorsForNonMatching=*/false)
    }

    /** Returns an equivalent instance but only containing artifacts of the given types.  */
    fun filter(fileTypeSet: FileTypeSet): PrerequisiteArtifacts {
        return filter(fileTypeSet,  /*errorsForNonMatching=*/false)
    }

    /**
     * Returns an equivalent instance but only containing artifacts of the given type, reporting
     * errors for non-matching artifacts.
     */
    fun errorsForNonMatching(fileType: FileType): PrerequisiteArtifacts {
        return filter(fileType,  /*errorsForNonMatching=*/true)
    }

    /**
     * Returns an equivalent instance but only containing artifacts of the given types, reporting
     * errors for non-matching artifacts.
     */
    fun errorsForNonMatching(fileTypeSet: FileTypeSet): PrerequisiteArtifacts {
        return filter(fileTypeSet,  /*errorsForNonMatching=*/true)
    }

    companion object {
        fun get(ruleContext: RuleContext, attributeName: String?): PrerequisiteArtifacts {
            val prerequisites: com.google.common.collect.ImmutableList<FileProvider> =
                com.google.common.collect.ImmutableList.copyOf(
                    ruleContext.getPrerequisites(
                        attributeName,
                        FileProvider::class.java
                    )
                )
            // Fast path #1: Many attributes are not set.
            if (prerequisites.isEmpty()) {
                return PrerequisiteArtifacts(
                    ruleContext,
                    attributeName,
                    com.google.common.collect.ImmutableList.of<Artifact?>()
                )
            }
            // Fast path #2: Often, attributes are set exactly once. In this case, we can completely elide
            // additional copies as the getFilesToBuild() call already returns an ImmutableList of the
            // expanded NestedSet.
            if (prerequisites.size() == 1) {
                return PrerequisiteArtifacts(
                    ruleContext, attributeName, prerequisites.get(0).getFilesToBuild().toList()
                )
            }
            val result: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            for (target in prerequisites) {
                result.addAll(target.getFilesToBuild().toList())
            }
            return PrerequisiteArtifacts(ruleContext, attributeName, result.build().asList())
        }

        fun nestedSet(
            prerequisitesCollection: PrerequisitesCollection, attributeName: String?
        ): NestedSet<Artifact?> {
            val result: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            for (target in prerequisitesCollection.getPrerequisites(attributeName, FileProvider::class.java)) {
                result.addTransitive(target.getFilesToBuild())
            }
            return result.build()
        }
    }
}
