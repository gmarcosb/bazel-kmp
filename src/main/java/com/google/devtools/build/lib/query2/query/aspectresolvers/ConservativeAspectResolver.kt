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
import com.google.common.collect.ImmutableSetMultimap
import com.google.devtools.build.lib.cmdline.Label

/**
 * An aspect resolver that overestimates the required aspect dependencies.
 * 
 * 
 * Does not need to load any packages other than the one containing the target being processed.
 */
class ConservativeAspectResolver : AspectResolver {
    override fun computeAspectDependencies(
        target: Target?, dependencyFilter: DependencyFilter?
    ): ImmutableMap<Aspect?, ImmutableMultimap<Attribute?, Label?>?> {
        if (target !is Rule) {
            return ImmutableMap.of<Aspect?, ImmutableMultimap<Attribute?, Label?>?>()
        }
        if (!target.hasAspects()) {
            return ImmutableMap.of<Aspect?, ImmutableMultimap<Attribute?, Label?>?>()
        }

        val results: LinkedHashMap<Aspect?, ImmutableMultimap<Attribute?, Label?>?> =
            LinkedHashMap<Aspect?, ImmutableMultimap<Attribute?, Label?>?>()

        for (attribute in target.getAttributes()) {
            for (aspect in attribute.getAspects(target)) {
                val attributeLabelsBuilder: ImmutableSetMultimap.Builder<Attribute?, Label?> =
                    ImmutableSetMultimap.builder<Attribute?, Label?>()
                AspectDefinition.forEachLabelDepFromAllAttributesOfAspect(
                    aspect, dependencyFilter, attributeLabelsBuilder::put
                )
                val attributeLabels: ImmutableSetMultimap<Attribute?, Label?> = attributeLabelsBuilder.build()
                if (!attributeLabels.isEmpty()) {
                    results.put(aspect, attributeLabels)
                }
            }
        }

        return ImmutableMap.copyOf<Aspect?, ImmutableMultimap<Attribute?, Label?>?>(results)
    }

    override fun computeBuildFileDependencies(buildFile: Target): ImmutableList<Label?> {
        // We do a conservative estimate precisely so that we don't depend on any other BUILD files.
        return buildFile.getPackageDeclarations().getOrComputeTransitivelyLoadedStarlarkFiles()
    }
}
