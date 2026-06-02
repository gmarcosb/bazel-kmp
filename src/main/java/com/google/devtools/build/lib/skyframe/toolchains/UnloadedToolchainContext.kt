// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.analysis.ToolchainContext

/**
 * Represents the state of toolchain resolution once the specific required toolchains have been
 * determined, but before the toolchain dependencies have been resolved.
 */
interface UnloadedToolchainContext : ToolchainContext, SkyValue {
    /** The map of toolchain type to resolved toolchain to be used.  */
    fun toolchainTypeToResolved(): com.google.common.collect.ImmutableSetMultimap<ToolchainTypeInfo?, Label?>?

    /**
     * Maps from the actual requested [Label] to the discovered [ToolchainTypeInfo].
     * 
     * 
     * Note that the key may be different from [ToolchainTypeInfo.typeLabel] if the
     * requested [Label] is an `alias`. In this case, there will be two [ labels][Label] for the same [ToolchainTypeInfo].
     */
    fun requestedLabelToToolchainType(): com.google.common.collect.ImmutableMap<Label?, ToolchainTypeInfo?>?

    public override fun resolvedToolchainLabels(): com.google.common.collect.ImmutableSet<Label?>?

    /** Returns the error, if any, that occurred in resolving this toolchain context.  */
    fun errorData(): NoMatchingPlatformData?
}
