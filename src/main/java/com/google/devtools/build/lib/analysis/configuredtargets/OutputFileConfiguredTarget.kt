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

/** A ConfiguredTarget for an OutputFile.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoCodec
class OutputFileConfiguredTarget @AutoCodec.Instantiator @VisibleForSerialization internal constructor(
    lookupKey: ActionLookupKey?,
    visibility: NestedSet<PackageGroupContents?>?,
    artifact: Artifact?,
    generatingRule: RuleConfiguredTarget?
) : FileConfiguredTarget(lookupKey, visibility, artifact) {
    private val generatingRule: RuleConfiguredTarget

    constructor(targetContext: TargetContext, outputArtifact: Artifact?, generatingRule: RuleConfiguredTarget?) : this(
        targetContext.getAnalysisEnvironment().getOwner(),
        targetContext.getVisibility(),
        outputArtifact,
        com.google.common.base.Preconditions.checkNotNull<RuleConfiguredTarget?>(generatingRule)
    ) {
        com.google.common.base.Preconditions.checkArgument(
            targetContext.getTarget() is OutputFile,
            targetContext.getTarget()
        )
    }

    init {
        this.generatingRule = com.google.common.base.Preconditions.checkNotNull<RuleConfiguredTarget>(generatingRule)
    }

    fun getGeneratingRule(): RuleConfiguredTarget {
        return generatingRule
    }

    public override fun isCreatedInSymbolicMacro(): Boolean {
        return generatingRule.isCreatedInSymbolicMacro()
    }

    override fun createTransitiveVisibilityProvider(): TransitiveVisibilityProvider? {
        return generatingRule.getProvider<TransitiveVisibilityProvider?>(TransitiveVisibilityProvider::class.java)
    }

    override fun <P : TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>?): P? {
        val provider: P? = super.getProvider<P?>(providerClass)
        if (provider != null) {
            return provider
        }
        if (providerClass == RequiredConfigFragmentsProvider::class.java) {
            return generatingRule.getProvider<P?>(providerClass)
        }
        return null
    }

    override fun rawGetStarlarkProvider(providerKey: Provider.Key): Info? {
        // The following Starlark providers do not implement TransitiveInfoProvider and thus may only be
        // requested via this method using a Provider.Key, not via getProvider(Class) above.

        if (providerKey.equals(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR.getKey())) {
            return com.google.common.base.MoreObjects.firstNonNull<T?>(
                generatingRule.get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR),
                InstrumentedFilesInfo.EMPTY
            )
        }

        if (providerKey.equals(OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR.getKey())) {
            // We have an OutputFileConfiguredTarget, so the generating rule must have OutputGroupInfo.
            val validationOutputs: NestedSet<Artifact?> =
                generatingRule
                    .get(OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR)
                    .getOutputGroup(OutputGroupInfo.Companion.VALIDATION)
            if (!validationOutputs.isEmpty()) {
                return OutputGroupInfo.Companion.singleGroup(OutputGroupInfo.Companion.VALIDATION, validationOutputs)
            }
        }

        return null
    }

    override fun getProvidersDictForQuery(): Dict<String?, Any?>? {
        val dict: net.starlark.java.eval.Dict.Builder<String?, Any?> = Dict.builder<String?, Any?>()
        dict.putAll(super.getProvidersDictForQuery())
        addStarlarkProviderIfPresent(dict, InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
        addStarlarkProviderIfPresent(dict, OutputGroupInfo.Companion.STARLARK_CONSTRUCTOR)
        addNativeProviderFromRuleIfPresent(dict, RequiredConfigFragmentsProvider::class.java)
        return dict.buildImmutable()
    }

    private fun addStarlarkProviderIfPresent(
        dict: net.starlark.java.eval.Dict.Builder<String?, Any?>?,
        provider: Provider
    ) {
        val info: Info? = rawGetStarlarkProvider(provider.getKey())
        if (info != null) {
            AbstractConfiguredTarget.Companion.tryAddProviderForQuery(dict, provider.getKey(), info)
        }
    }

    private fun addNativeProviderFromRuleIfPresent(
        dict: net.starlark.java.eval.Dict.Builder<String?, Any?>?,
        providerClass: java.lang.Class<out TransitiveInfoProvider?>?
    ) {
        val provider: TransitiveInfoProvider? = generatingRule.getProvider(providerClass)
        if (provider != null) {
            AbstractConfiguredTarget.Companion.tryAddProviderForQuery(dict, providerClass, provider)
        }
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<output file target " + getLabel() + ">")
    }
}
