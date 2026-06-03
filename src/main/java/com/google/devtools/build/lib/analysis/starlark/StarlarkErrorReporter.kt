// Copyright 2018 The Bazel Authors. All rights reserved.
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
// limitations under the License
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.RuleErrorConsumer

/**
 * [RuleErrorConsumer] for Native implementations of Starlark APIs.
 * 
 * 
 * This class proxies reported errors and warnings to a proxy [RuleErrorConsumer], except
 * that it suppresses all cases of actually throwing exceptions until this reporter is closed.
 * 
 * 
 * This class is AutoClosable, to ensure that [RuleErrorException] are checked and handled
 * before leaving native code. The [.close] method will only throw [EvalException],
 * properly wrapping any [RuleErrorException] instances if needed.
 */
class StarlarkErrorReporter private constructor(ruleErrorConsumer: RuleErrorConsumer) : java.lang.AutoCloseable,
    RuleErrorConsumer {
    private val ruleErrorConsumer: RuleErrorConsumer

    init {
        this.ruleErrorConsumer = ruleErrorConsumer
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun close() {
        try {
            assertNoErrors()
        } catch (e: RuleErrorException) {
            throw Starlark.errorf("error occurred while evaluating builtin function: %s", e.getMessage())
        }
    }

    public override fun ruleWarning(message: String?) {
        ruleErrorConsumer.ruleWarning(message)
    }

    public override fun ruleError(message: String?) {
        ruleErrorConsumer.ruleError(message)
    }

    public override fun attributeWarning(attrName: String?, message: String?) {
        ruleErrorConsumer.attributeWarning(attrName, message)
    }

    public override fun attributeError(attrName: String?, message: String?) {
        ruleErrorConsumer.attributeError(attrName, message)
    }

    public override fun hasErrors(): Boolean {
        return ruleErrorConsumer.hasErrors()
    }

    companion object {
        fun from(ruleErrorConsumer: RuleErrorConsumer): StarlarkErrorReporter {
            return StarlarkErrorReporter(ruleErrorConsumer)
        }
    }
}
