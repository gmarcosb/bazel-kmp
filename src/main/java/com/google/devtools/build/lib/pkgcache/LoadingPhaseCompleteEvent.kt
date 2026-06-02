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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.Label

/**
 * This event is fired after the loading phase is complete.
 */
class LoadingPhaseCompleteEvent(
    labels: com.google.common.collect.ImmutableSet<Label?>?,
    filteredLabels: com.google.common.collect.ImmutableSet<Label?>?,
    mainRepositoryMapping: RepositoryMapping?
) : Postable {
    private val labels: com.google.common.collect.ImmutableSet<Label?>
    private val filteredLabels: com.google.common.collect.ImmutableSet<Label?>
    private val mainRepositoryMapping: RepositoryMapping

    /**
     * Construct the event.
     * 
     * @param labels the set of active targets that remain
     * @param filteredLabels the set of filtered targets
     */
    init {
        this.labels =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<Label?>>(labels)
        this.filteredLabels =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<Label?>>(
                filteredLabels
            )
        this.mainRepositoryMapping =
            com.google.common.base.Preconditions.checkNotNull<RepositoryMapping>(mainRepositoryMapping)
    }

    /**
     * @return The set of active target labels remaining, which is a subset of the
     * targets we attempted to load.
     */
    fun getLabels(): com.google.common.collect.ImmutableSet<Label?> {
        return labels
    }

    /**
     * @return The set of filtered targets.
     */
    fun getFilteredLabels(): com.google.common.collect.ImmutableSet<Label?> {
        return filteredLabels
    }

    /**
     * @return The repository mapping of the main repository.
     */
    fun getMainRepositoryMapping(): RepositoryMapping {
        return mainRepositoryMapping
    }

    public override fun storeForReplay(): Boolean {
        return true
    }
}
