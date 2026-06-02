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

import com.google.common.collect.*
import com.google.devtools.build.lib.cmdline.Label

/**
 * An aspect resolver that returns only those aspects that are possibly active given the rule
 * classes of direct dependencies.
 * 
 * 
 * Needs to load the packages that contain dependencies through attributes with aspects.
 */
class PreciseAspectResolver(packageProvider: PackageProvider, eventHandler: ExtendedEventHandler?) : AspectResolver {
    private val packageProvider: PackageProvider
    private val eventHandler: ExtendedEventHandler?

    init {
        this.packageProvider = packageProvider
        this.eventHandler = eventHandler
    }

    @Throws(InterruptedException::class)
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
        val transitions: Multimap<Attribute, Label?> =
            target.getTransitions(DependencyFilter.NO_NODEP_ATTRIBUTES)
        for (attribute in transitions.keySet()) {
            for (aspect in attribute.getAspects(target)) {
                if (hasDepThatSatisfies(aspect, transitions.get(attribute))) {
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
        }
        return ImmutableMap.copyOf<Aspect?, ImmutableMultimap<Attribute?, Label?>?>(results)
    }

    @Throws(InterruptedException::class)
    private fun hasDepThatSatisfies(aspect: Aspect?, labelDeps: Iterable<Label?>): Boolean {
        for (toLabel in labelDeps) {
            val toTarget: Target
            try {
                toTarget = packageProvider.getTarget(eventHandler, toLabel)
            } catch (e: NoSuchThingException) {
                // Do nothing interesting. One of target direct deps has an error. The dependency on the
                // BUILD file (or one of the files included in it) will be reported in the query result of
                // :BUILD.
                continue
            }
            if (toTarget !is Rule) {
                continue
            }
            if (AspectDefinition.satisfies(
                    aspect, (toTarget as Rule).getRuleClassObject().getAdvertisedProviders()
                )
            ) {
                return true
            }
        }
        return false
    }

    @Throws(InterruptedException::class)
    private fun getSiblingTargets(buildFile: Target): ImmutableCollection<Target?> {
        try {
            return packageProvider.getSiblingTargetsInPackage(eventHandler, buildFile)
        } catch (e: NoSuchPackageException) {
            // If we fail to expand the full package (e.g. because a package piece for a symbolic macro
            // is in error), fall back to iterating only over the targets in the BUILD file's package
            // piece. The error encountered will be reported in the eventHandler.
            return buildFile.getPackageoid().getTargets().values()
        }
    }

    @Throws(InterruptedException::class)
    override fun computeBuildFileDependencies(buildFile: Target): MutableSet<Label?> {
        val result: MutableSet<Label?> = LinkedHashSet<Label?>()
        buildFile.getPackageDeclarations().visitLoadGraph(result::add)

        val dependentPackages: MutableSet<PackageIdentifier?> = LinkedHashSet<PackageIdentifier?>()
        // First compute what packages can possibly affect the aspect attributes of this package:
        // Iterate over all rules...
        for (target in getSiblingTargets(buildFile)) {
            if (target !is Rule) {
                continue
            }

            // ...figure out which direct dependencies can possibly have aspects attached to them...
            val depsWithPossibleAspects: Multimap<Attribute?, Label> =
                target.getTransitions(
                    { infoProvider, attribute ->
                        for (aspectWithParameters in attribute.getAspects(target)) {
                            if (!aspectWithParameters.getDefinition().getAttributes().isEmpty()) {
                                return@getTransitions true
                            }
                        }
                        false
                    })

            // ...and add the package of the aspect.
            for (depLabel in depsWithPossibleAspects.values()) {
                dependentPackages.add(depLabel.getPackageIdentifier())
            }
        }

        // Then add all the labels of all the bzl files loaded by the packages found.
        for (packageIdentifier in dependentPackages) {
            try {
                result.add(Label.create(packageIdentifier, "BUILD"))
                val dependentPackage: Package = packageProvider.getPackage(eventHandler, packageIdentifier)
                dependentPackage.getDeclarations().visitLoadGraph(result::add)
            } catch (e: NoSuchPackageException) {
                // If the package is not found, just add its BUILD file, which is already done above.
                // Hopefully this error is not raised when there is a syntax error in a subincluded file
                // or something.
            } catch (e: LabelSyntaxException) {
                throw IllegalStateException(e)
            }
        }

        return result
    }
}
