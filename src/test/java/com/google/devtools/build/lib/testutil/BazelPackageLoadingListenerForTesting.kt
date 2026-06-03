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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * A [PackageLoadingListener] for use in tests that a check with [BazelPackageLoader]
 * for each loaded package, for the sake of getting pretty nice test coverage.
 */
class BazelPackageLoadingListenerForTesting(
    ruleClassProvider: ConfiguredRuleClassProvider?,
    directories: BlazeDirectories,
    extraPrecomputedValues: com.google.common.collect.ImmutableList<Injected?>?,
    extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?
) : PackageLoadingListener {
    private val ruleClassProvider: ConfiguredRuleClassProvider?
    private val directories: BlazeDirectories
    private val extraPrecomputedValues: com.google.common.collect.ImmutableList<Injected?>?
    private val extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?

    init {
        this.ruleClassProvider = ruleClassProvider
        this.directories = directories
        this.extraPrecomputedValues = extraPrecomputedValues
        this.extraSkyFunctions = extraSkyFunctions
    }

    public override fun onLoadingCompleteAndSuccessful(
        pkg: Package,
        starlarkSemantics: StarlarkSemantics?,
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?,
        metrics: Metrics?
    ) {
        sanityCheckBazelPackageLoader(
            pkg, ruleClassProvider, starlarkSemantics, lazyMacroExpansionPackages
        )
    }

    private fun makeFreshPackageLoader(
        ruleClassProvider: ConfiguredRuleClassProvider?,
        starlarkSemantics: StarlarkSemantics?,
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?
    ): PackageLoader {
        return BazelPackageLoader.builder(
            Root.fromPath(directories.getWorkspace()),
            directories.getInstallBase(),
            directories.getOutputBase()
        )
            .setStarlarkSemantics(starlarkSemantics)
            .setRuleClassProvider(ruleClassProvider)
            .addExtraPrecomputedValues(extraPrecomputedValues)
            .addExtraSkyFunctions(extraSkyFunctions)
            .setLazyMacroExpansionPackages(lazyMacroExpansionPackages)
            .build()
    }

    private fun sanityCheckBazelPackageLoader(
        pkg: Package,
        ruleClassProvider: ConfiguredRuleClassProvider?,
        starlarkSemantics: StarlarkSemantics?,
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?
    ) {
        val pkgId: PackageIdentifier? = pkg.getPackageIdentifier()
        val newlyLoadedPkg: Package
        try {
            makeFreshPackageLoader(
                ruleClassProvider,
                starlarkSemantics,
                lazyMacroExpansionPackages
            ).use { packageLoader ->
                newlyLoadedPkg = packageLoader.loadPackage(pkg.getPackageIdentifier())
            }
        } catch (e: java.lang.InterruptedException) {
            return
        } catch (e: NoSuchPackageException) {
            throw java.lang.IllegalStateException(e)
        }
        val targetsInPkg: com.google.common.collect.ImmutableSet<Label?> =
            com.google.common.collect.ImmutableSet.copyOf<E?>(
                com.google.common.collect.Iterables.transform<F?, T?>(
                    pkg.getTargets().values(), Target::getLabel
                )
            )
        val targetsInNewlyLoadedPkg: com.google.common.collect.ImmutableSet<Label?> =
            com.google.common.collect.ImmutableSet.copyOf<E?>(
                com.google.common.collect.Iterables.transform<F?, T?>(
                    newlyLoadedPkg.getTargets().values(),
                    Target::getLabel
                )
            )
        if (targetsInPkg != targetsInNewlyLoadedPkg) {
            val unsatisfied: com.google.common.collect.Sets.SetView<Label?> =
                com.google.common.collect.Sets.difference<Label?>(targetsInPkg, targetsInNewlyLoadedPkg)
            val unexpected: com.google.common.collect.Sets.SetView<Label?> =
                com.google.common.collect.Sets.difference<Label?>(targetsInNewlyLoadedPkg, targetsInPkg)
            throw java.lang.IllegalStateException(
                String.format(
                    ("The Package for %s had a different set of targets (<targetsInPkg> - "
                            + "<targetsInNewlyLoadedPkg> = %s, <targetsInNewlyLoadedPkg> - <targetsInPkg> = "
                            + "%s) when loaded normally during execution of the current test than it did "
                            + "when loaded via BazelPackageLoader (done automatically by the "
                            + "BazelPackageLoadingListenerForTesting hook). This either means: (i) Skyframe "
                            + "package loading semantics have diverged from "
                            + "BazelPackageLoader semantics (ii) The test in question is doing something "
                            + "that confuses BazelPackageLoadingListenerForTesting."),
                    pkgId, unsatisfied, unexpected
                )
            )
        }
    }
}
