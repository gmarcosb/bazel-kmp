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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * This class builds transitive Target values such that evaluating a Target value is similar to
 * running it through the LabelVisitor.
 */
internal class TransitiveTargetFunction

    : TransitiveBaseTraversalFunction<TransitiveTargetValueBuilder?>() {
    override fun argumentFromKey(key: SkyKey): Label {
        return (key as TransitiveTargetKey).getLabel()
    }

    override fun getKey(label: Label?): SkyKey? {
        return TransitiveTargetKey.Companion.of(label)
    }

    override fun processTarget(
        targetAndErrorIfAny: TargetAndErrorIfAny
    ): TransitiveTargetValueBuilder {
        val target: Target = targetAndErrorIfAny.getTarget()
        val packageLoadedSuccessfully: Boolean = targetAndErrorIfAny.isPackageLoadedSuccessfully()
        return TransitiveTargetValueBuilder(target, packageLoadedSuccessfully)
    }

    public override fun processDeps(
        builder: TransitiveTargetValueBuilder,
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        targetAndErrorIfAny: TargetAndErrorIfAny,
        depEntries: SkyframeLookupResult,
        depKeys: Iterable<out SkyKey>
    ) {
        var successfulTransitiveLoading = builder.isSuccessfulTransitiveLoading
        val target: Target = targetAndErrorIfAny.getTarget()

        for (skyKey in depKeys) {
            val depLabel: Label = (skyKey as TransitiveTargetKey).getLabel()
            val transitiveTargetValue: TransitiveTargetValue?
            try {
                transitiveTargetValue =
                    depEntries.getOrThrow<E1?, E2?>(
                        skyKey, NoSuchPackageException::class.java, NoSuchTargetException::class.java
                    ) as TransitiveTargetValue?
            } catch (e: NoSuchPackageException) {
                successfulTransitiveLoading = false
                maybeReportErrorAboutMissingEdge(target, depLabel, e, eventHandler)
                continue
            } catch (e: NoSuchTargetException) {
                successfulTransitiveLoading = false
                maybeReportErrorAboutMissingEdge(target, depLabel, e, eventHandler)
                continue
            }
            if (transitiveTargetValue == null) {
                BugReport.sendNonFatalBugReport(
                    java.lang.IllegalStateException(
                        "TransitiveTargetValue " + skyKey + " was missing, this should never happen"
                    )
                )
                continue
            }
            builder.getTransitiveTargets().addTransitive(transitiveTargetValue.getTransitiveTargets())
            if (transitiveTargetValue.encounteredLoadingError()) {
                successfulTransitiveLoading = false
                if (transitiveTargetValue.getErrorLoadingTarget() != null) {
                    maybeReportErrorAboutMissingEdge(
                        target, depLabel,
                        transitiveTargetValue.getErrorLoadingTarget(), eventHandler
                    )
                }
            }
        }

        builder.isSuccessfulTransitiveLoading = successfulTransitiveLoading
    }

    public override fun computeSkyValue(
        targetAndErrorIfAny: TargetAndErrorIfAny,
        builder: TransitiveTargetValueBuilder
    ): SkyValue {
        val errorLoadingTarget: NoSuchTargetException? = targetAndErrorIfAny.getErrorLoadingTarget()
        return builder.build(errorLoadingTarget)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getAdvertisedProviderSet(
        toLabel: Label, toVal: SkyValue?, env: SkyFunction.Environment
    ): AdvertisedProviderSet? {
        val packageKey: SkyKey? = toLabel.getPackageIdentifier()
        val toTarget: Target
        try {
            val pkgValue: PackageValue? =
                env.getValueOrThrow<E?>(packageKey, NoSuchPackageException::class.java) as PackageValue?
            if (pkgValue == null) {
                return null
            }
            val pkg: Package = pkgValue.getPackage()
            if (pkg.containsErrors()) {
                // Do nothing interesting. This error was handled when we computed the corresponding
                // TransitiveTargetValue.
                return null
            }
            toTarget = pkgValue.getPackage().getTarget(toLabel.name)
        } catch (e: NoSuchThingException) {
            // Do nothing interesting. This error was handled when we computed the corresponding
            // TransitiveTargetValue.
            return null
        }
        if (toTarget !is Rule) {
            // Aspect can be declared only for Rules.
            return null
        }
        return (toTarget as Rule).getRuleClassObject().getAdvertisedProviders()
    }

    /**
     * Holds values accumulated across the given target and its transitive dependencies for the
     * purpose of constructing a [TransitiveTargetValue].
     * 
     * 
     * Note that this class is mutable! The `successfulTransitiveLoading` property is
     * initialized with the `packageLoadedSuccessfully` constructor parameter, and may be
     * modified if a transitive dependency is found to be in error.
     */
    internal class TransitiveTargetValueBuilder(target: Target, var isSuccessfulTransitiveLoading: Boolean) {
        private val transitiveTargets: NestedSetBuilder<Label?>

        init {
            this.transitiveTargets = NestedSetBuilder.stableOrder()
            transitiveTargets.add(target.getLabel())
        }

        fun getTransitiveTargets(): NestedSetBuilder<Label?> {
            return transitiveTargets
        }

        fun build(errorLoadingTarget: NoSuchTargetException?): SkyValue {
            val loadedTargets: NestedSet<Label?>? = transitiveTargets.build()
            return if (this.isSuccessfulTransitiveLoading)
                TransitiveTargetValue.Companion.successfulTransitiveLoading(loadedTargets)
            else
                TransitiveTargetValue.Companion.unsuccessfulTransitiveLoading(loadedTargets, errorLoadingTarget)
        }
    }

    companion object {
        private fun maybeReportErrorAboutMissingEdge(
            target: Target?,
            depLabel: Label,
            e: NoSuchThingException?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler
        ) {
            if (e is NoSuchTargetException) {
                if (depLabel.equals(e.getLabel())) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            TargetUtils.getLocationMaybe(target),
                            TargetUtils.formatMissingEdge(target, depLabel, e)
                        )
                    )
                }
            } else if (e is NoSuchPackageException) {
                if (e.getPackageId().equals(depLabel.getPackageIdentifier())) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.error(
                            TargetUtils.getLocationMaybe(target),
                            TargetUtils.formatMissingEdge(target, depLabel, e)
                        )
                    )
                }
            }
        }
    }
}
