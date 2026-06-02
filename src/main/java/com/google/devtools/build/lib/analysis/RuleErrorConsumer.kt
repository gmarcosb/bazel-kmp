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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException

/**
 * A thin interface exposing only the warning and error reporting functionality
 * of a rule.
 * 
 * 
 * When a class or a method needs only this functionality but not the whole
 * `RuleContext`, it can use this thin interface instead.
 * 
 * 
 * This interface should only be implemented by `RuleContext`.
 */
interface RuleErrorConsumer {
    /**
     * Consume a non-attribute-specific warning in a rule.
     */
    fun ruleWarning(message: String?)

    /**
     * Consume a non-attribute-specific error in a rule.
     */
    fun ruleError(message: String?)

    /**
     * Consume an attribute-specific warning in a rule.
     */
    fun attributeWarning(attrName: String?, message: String?)

    /**
     * Consume an attribute-specific error in a rule.
     */
    fun attributeError(attrName: String?, message: String?)

    /**
     * Convenience function to report non-attribute-specific errors in the current rule and then throw
     * a [RuleErrorException], immediately exiting the current rule, and shutting down the
     * invocation in a no-keep-going build. If multiple errors are present, invoke [.ruleError]
     * to collect additional error information before calling this method.
     */
    // TODO(bazel-team): Consider not throwing and instead just returning the exception, thereby
    // forcing the caller to use the throw statement instead of abstracting the control flow (which
    // can hurt readability).
    @Throws(RuleErrorException::class)
    fun throwWithRuleError(message: String?): RuleErrorException? {
        ruleError(message)
        throw RuleErrorException(message)
    }

    /** See [.throwWithRuleError].  */
    @Throws(RuleErrorException::class)
    fun throwWithRuleError(cause: Throwable): RuleErrorException? {
        ruleError(cause.message)
        throw RuleErrorException(cause)
    }

    /** See [.throwWithRuleError].  */
    @Throws(RuleErrorException::class)
    fun throwWithRuleError(message: String?, cause: Throwable?): RuleErrorException? {
        ruleError(message)
        throw RuleErrorException(message, cause)
    }

    /**
     * Convenience function to report attribute-specific errors in the current rule, and then throw a
     * [RuleErrorException], immediately exiting the build invocation. Alternatively, invoke
     * [.attributeError] instead to collect additional error information before ending the
     * invocation.
     * 
     * 
     * If the name of the attribute starts with `$`
     * it is replaced with a string `(an implicit dependency)`.
     */
    @Throws(RuleErrorException::class)
    fun throwWithAttributeError(attrName: String?, message: String?): RuleErrorException? {
        attributeError(attrName, message)
        throw RuleErrorException(message)
    }

    /**
     * Returns whether this instance is known to have errors at this point during analysis. Do not
     * call this method after the initializationHook has returned.
     */
    fun hasErrors(): Boolean

    /**
     * No-op if [.hasErrors] is false, throws [RuleErrorException] if it is true.
     * This provides a convenience to early-exit of configured target creation if there are errors.
     */
    @Throws(RuleErrorException::class)
    fun assertNoErrors() {
        if (hasErrors()) {
            throw RuleErrorException()
        }
    }
}
