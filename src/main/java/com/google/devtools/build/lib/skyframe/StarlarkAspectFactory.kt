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

import com.google.devtools.build.lib.actions.ActionConflictException

/** A factory for aspects that are defined in Starlark.  */
class StarlarkAspectFactory internal constructor(starlarkAspect: StarlarkDefinedAspect) : ConfiguredAspectFactory {
    private val starlarkAspect: StarlarkDefinedAspect

    init {
        this.starlarkAspect = starlarkAspect
    }

    @Throws(java.lang.InterruptedException::class, ActionConflictException::class)
    public override fun create(
        targetLabel: Label?,
        ct: ConfiguredTarget?,
        ruleContext: RuleContext,
        parameters: AspectParameters?,
        toolsRepository: RepositoryName?
    ): ConfiguredAspect? {
        val requiredConfigFragments: RequiredConfigFragmentsProvider?
        var aspectStarlarkObject: Any
        try {
            val ctx: StarlarkRuleContext? = ruleContext.initStarlarkRuleContext()
            aspectStarlarkObject =
                net.starlark.java.eval.Starlark.positionalOnlyCall(
                    ruleContext.getStarlarkThread(), starlarkAspect.getImplementation(), ct, ctx
                )
        } catch (e: RuleErrorException) {
            // TODO(bazel-team): Doesn't this double-log the message, if the exception was created by
            // RuleContext#throwWithRuleError?
            ruleContext.ruleError(e.getMessage())
            return errorConfiguredAspect(ruleContext)
        } catch (ex: net.starlark.java.eval.Starlark.UncheckedEvalException) {
            // MissingDepException is expected to transit through Starlark execution.
            throw if (ex.getCause() is CachingAnalysisEnvironment.MissingDepException)
                ex.getCause() as CachingAnalysisEnvironment.MissingDepException?
            else
                ex
        } catch (e: net.starlark.java.eval.EvalException) {
            ruleContext.ruleError("\n" + e.getMessageWithStack())
            return errorConfiguredAspect(ruleContext)
        } finally {
            requiredConfigFragments = ruleContext.getRequiredConfigFragments()
            // freeze mutability to allow optimizing StarlarkInfo instances
            ruleContext.close()
        }
        // If allowing analysis failures, targets should be created somewhat normally, and errors
        // will be propagated via a hook elsewhere as AnalysisFailureInfo.
        val allowAnalysisFailures: Boolean = ruleContext.getConfiguration().allowAnalysisFailures()

        if (ruleContext.hasErrors() && !allowAnalysisFailures) {
            return errorConfiguredAspect(ruleContext, requiredConfigFragments)
        } else if (aspectStarlarkObject is Info
            && aspectStarlarkObject.getProvider().getKey().equals(StructProvider.STRUCT.key)
        ) {
            ruleContext.ruleError(
                "Returning a struct from an aspect implementation function is deprecated."
            )
        } else if (aspectStarlarkObject !is Iterable<*> && aspectStarlarkObject !is Info) {
            ruleContext.ruleError(
                java.lang.String.format(
                    "Aspect implementation should return a list, or a provider instance, but got %s",
                    net.starlark.java.eval.Starlark.type(aspectStarlarkObject)
                )
            )
            return errorConfiguredAspect(ruleContext, requiredConfigFragments)
        }
        try {
            return createAspect(aspectStarlarkObject, ruleContext, requiredConfigFragments)
        } catch (e: net.starlark.java.eval.EvalException) {
            ruleContext.ruleError("\n" + e.getMessageWithStack())
            return errorConfiguredAspect(ruleContext, requiredConfigFragments)
        }
    }

    companion object {
        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        private fun errorConfiguredAspect(ruleContext: RuleContext): ConfiguredAspect {
            return errorConfiguredAspect(ruleContext, ruleContext.getRequiredConfigFragments())
        }

        @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
        private fun errorConfiguredAspect(
            ruleContext: RuleContext?, requiredConfigFragmentsProvider: RequiredConfigFragmentsProvider?
        ): ConfiguredAspect {
            return ConfiguredTargetFactory.erroredConfiguredAspect(
                ruleContext, requiredConfigFragmentsProvider
            )
        }

        @Throws(
            net.starlark.java.eval.EvalException::class,
            ActionConflictException::class,
            java.lang.InterruptedException::class
        )
        private fun createAspect(
            aspectStarlarkObject: Any?,
            ruleContext: RuleContext?,
            requiredConfigFragments: RequiredConfigFragmentsProvider?
        ): ConfiguredAspect? {
            val builder: ConfiguredAspect.Builder = Builder(ruleContext)
            if (requiredConfigFragments != null) {
                builder.addProvider(requiredConfigFragments)
            }
            // not instanceof Info, because OutputGroupInfo is both Iterable and Info
            if (aspectStarlarkObject !is Info && aspectStarlarkObject is Iterable<*>) {
                addDeclaredProviders(builder, aspectStarlarkObject)
            } else {
                // A single declared provider (not in a list)
                var info: Info? = aspectStarlarkObject as Info?
                if (info is StarlarkInfo) {
                    info = info.unsafeOptimizeMemoryLayout()
                }
                builder.addStarlarkDeclaredProvider(info)
            }

            val configuredAspect: ConfiguredAspect? = builder.build()
            StarlarkProviderValidationUtil.validateArtifacts(ruleContext)
            return configuredAspect
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun addDeclaredProviders(
            builder: ConfiguredAspect.Builder, aspectStarlarkObject: Iterable<*>
        ) {
            var i = 0
            for (o in aspectStarlarkObject) {
                var o: Any = o!!
                if (o !is Info) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "A return value of an aspect implementation function should be "
                                + "a sequence of declared providers, instead got a %s at index %d",
                        net.starlark.java.eval.Starlark.type(o), i
                    )
                }
                if (o is StarlarkInfo) {
                    o = o.unsafeOptimizeMemoryLayout()
                }
                builder.addStarlarkDeclaredProvider(o as Info?)
                i++
            }
        }
    }
}
