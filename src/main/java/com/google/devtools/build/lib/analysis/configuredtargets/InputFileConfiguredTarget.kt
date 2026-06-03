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
package com.google.devtools.build.lib.analysis.configuredtargets

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * A ConfiguredTarget for an InputFile.
 * 
 * 
 * All InputFiles for the same target are equivalent, so configuration does not play any role
 * here and is always set to **null**.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class InputFileConfiguredTarget @AutoCodec.Instantiator @VisibleForSerialization internal constructor(
    lookupKey: ActionLookupKey?,
    visibility: NestedSet<PackageGroupContents?>?,
    transitiveVisibilityImposedByThisPackage: PackageSpecificationProvider?,
    artifact: SourceArtifact?,
    private val isCreatedInSymbolicMacro: Boolean
) : FileConfiguredTarget(lookupKey, visibility, artifact) {
    private val transitiveVisibilityImposedByThisPackage: PackageSpecificationProvider?

    constructor(targetContext: TargetContext, artifact: SourceArtifact?) : this(
        targetContext.getAnalysisEnvironment().getOwner(),
        targetContext.getVisibility(),
        targetContext.getTransitiveVisibilityImposedByThisPackage(),
        artifact,
        targetContext.getTarget().isCreatedInSymbolicMacro()
    ) {
        com.google.common.base.Preconditions.checkArgument(
            targetContext.getTarget() is InputFile,
            targetContext.getTarget()
        )
        checkArgument(getConfigurationKey() == null, getLabel())
    }

    init {
        this.transitiveVisibilityImposedByThisPackage = transitiveVisibilityImposedByThisPackage
    }

    public override fun isCreatedInSymbolicMacro(): Boolean {
        return isCreatedInSymbolicMacro
    }

    override fun getArtifact(): SourceArtifact? {
        return super.getArtifact() as SourceArtifact?
    }

    override fun createTransitiveVisibilityProvider(): TransitiveVisibilityProvider? {
        // The inputFile has no deps, so the transitive visibility is only imposed by its package.
        return if (transitiveVisibilityImposedByThisPackage == null)
            null
        else
            TransitiveVisibilityProvider(
                com.google.common.collect.ImmutableSet.of<PackageSpecificationProvider?>(
                    transitiveVisibilityImposedByThisPackage
                )
            )
    }

    override fun rawGetStarlarkProvider(providerKey: Provider.Key?): Info? {
        return null
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<input file target " + getLabel() + ">")
    }

    override fun toString(): String {
        return "InputFileConfiguredTarget(" + getLabel() + ")"
    }
}
