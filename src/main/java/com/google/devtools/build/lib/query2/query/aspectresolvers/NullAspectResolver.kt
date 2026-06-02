// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query.aspectresolvers

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableMultimap
import com.google.devtools.build.lib.cmdline.Label

/**
 * An aspect resolver that does not return any aspect dependencies.
 * 
 * 
 * Simple, fast, wrong.
 */
class NullAspectResolver : AspectResolver {
    override fun computeAspectDependencies(
        target: Target?, dependencyFilter: DependencyFilter?
    ): ImmutableMap<Aspect?, ImmutableMultimap<Attribute?, Label?>?> {
        return ImmutableMap.of<Aspect?, ImmutableMultimap<Attribute?, Label?>?>()
    }

    override fun computeBuildFileDependencies(buildFile: Target): ImmutableList<Label?> {
        return buildFile.getPackageDeclarations().getOrComputeTransitivelyLoadedStarlarkFiles()
    }
}
