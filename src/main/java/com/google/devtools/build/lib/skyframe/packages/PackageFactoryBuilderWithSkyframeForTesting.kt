// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.packages

import com.google.devtools.build.lib.packages.PackageFactory

/**
 * A [PackageFactory.BuilderForTesting] that also allows specification of some skyframe
 * details.
 */
abstract class PackageFactoryBuilderWithSkyframeForTesting

    : PackageFactory.BuilderForTesting() {
    @kotlin.jvm.JvmField
    protected var extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>? =
        com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunction?>()
    @kotlin.jvm.JvmField
    protected var extraPrecomputedValues: com.google.common.collect.ImmutableList<Injected?> =
        com.google.common.collect.ImmutableList.of<Injected?>()

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setExtraSkyFunctions(
        extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?
    ): PackageFactoryBuilderWithSkyframeForTesting {
        this.extraSkyFunctions = extraSkyFunctions
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setExtraPrecomputeValues(
        extraPrecomputedValues: Iterable<Injected?>
    ): PackageFactoryBuilderWithSkyframeForTesting {
        this.extraPrecomputedValues = com.google.common.collect.ImmutableList.copyOf<Injected?>(extraPrecomputedValues)
        return this
    }
}
