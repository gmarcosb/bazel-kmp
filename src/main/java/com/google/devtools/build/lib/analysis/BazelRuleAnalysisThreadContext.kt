// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.Label

/** Bazel application data for the Starlark thread that performs analysis of rules and aspects.  */
class BazelRuleAnalysisThreadContext(ruleContext: RuleContext) :
    StarlarkThreadContext({ ruleContext.getAnalysisEnvironment().getMainRepoMapping() }) {
    private val ruleContext: RuleContext

    /**
     * Constructs a [BazelRuleAnalysisThreadContext].
     * 
     * @param ruleContext is the [RuleContext] of the rule for analysis of a rule or aspect
     */
    init {
        this.ruleContext = ruleContext
    }

    /** Returns the label of the rule.  */
    fun getAnalysisRuleLabel(): Label? {
        return ruleContext.getLabel()
    }

    fun getRuleContext(): RuleContext {
        return ruleContext
    }

    companion object {
        /**
         * Retrieves this context from a Starlark thread.
         * 
         * @param thread the [StarlarkThread] from which to retrieve the context
         * @param what information to include in the error thrown
         * @throws EvalException if not found
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(thread: StarlarkThread, what: String?): BazelRuleAnalysisThreadContext? {
            val ctx: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            if (ctx is BazelRuleAnalysisThreadContext) {
                return ctx
            }
            throw Starlark.errorf("%s can only be called from a rule or aspect implementation", what)
        }
    }
}
