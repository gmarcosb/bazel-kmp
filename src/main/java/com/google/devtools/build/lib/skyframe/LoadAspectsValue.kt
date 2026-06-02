// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.packages.Aspect

/**
 * [SkyValue] for `LoadAspectsKey` wraps a list of the `Aspect` of the top level
 * aspects.
 */
class LoadAspectsValue internal constructor(aspects: MutableCollection<Aspect?>) : SkyValue {
    private val aspects: com.google.common.collect.ImmutableList<Aspect?>

    init {
        this.aspects = com.google.common.collect.ImmutableList.copyOf<Aspect?>(aspects)
    }

    fun getAspects(): com.google.common.collect.ImmutableList<Aspect?> {
        return aspects
    }
}
