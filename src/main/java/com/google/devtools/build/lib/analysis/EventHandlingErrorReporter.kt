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
// limitations under the License.
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.Label

/**
 * Base class for implementations of [ ].
 * 
 * 
 * Do not create new implementations of this class - instead, use [RuleContext] in Native
 * rule definitions, and [StarlarkErrorReporter] in Starlark API definitions. For use in
 * testing, implement [RuleErrorConsumer] instead.
 */
abstract class EventHandlingErrorReporter protected constructor(
    private val ruleClassNameForLogging: String?,
    env: AnalysisEnvironment
) : RuleErrorConsumer {
    private val env: AnalysisEnvironment

    init {
        this.env = env
    }

    private fun reportError(location: net.starlark.java.syntax.Location?, message: String?) {
        // TODO(ulfjack): Consider generating the error message from the root cause event rather than
        // the other way round.
        if (!hasErrors()) {
            // We must not report duplicate events, so we only report the first one for now.
            val configuration: BuildConfigurationValue? = getConfiguration()
            env.getEventHandler()
                .post(AnalysisRootCauseEvent.Companion.withConfigurationValue(configuration, getLabel(), message))
        }
        env.getEventHandler().handle(com.google.devtools.build.lib.events.Event.error(location, message))
    }

    public override fun ruleError(message: String?) {
        reportError(getRuleLocation(), prefixRuleMessage(message))
    }

    public override fun attributeError(attrName: String?, message: String?) {
        reportError(getRuleLocation(), completeAttributeMessage(attrName, message))
    }

    public override fun hasErrors(): Boolean {
        return env.hasErrors()
    }

    fun reportWarning(location: net.starlark.java.syntax.Location?, message: String?) {
        env.getEventHandler().handle(com.google.devtools.build.lib.events.Event.warn(location, message))
    }

    public override fun ruleWarning(message: String?) {
        env.getEventHandler()
            .handle(com.google.devtools.build.lib.events.Event.warn(getRuleLocation(), prefixRuleMessage(message)))
    }

    public override fun attributeWarning(attrName: String?, message: String?) {
        reportWarning(getRuleLocation(), completeAttributeMessage(attrName, message))
    }

    private fun prefixRuleMessage(message: String?): String? {
        return java.lang.String.format("in %s rule %s: %s", ruleClassNameForLogging, getLabel(), message)
    }

    private fun maskInternalAttributeNames(name: String?): String? {
        return if (Attribute.isImplicit(name)) "(an implicit dependency)" else name
    }

    /**
     * Prefixes the given message with details about the rule and appends details about the macro that
     * created this rule, if applicable.
     */
    private fun completeAttributeMessage(attrName: String?, message: String?): String? {
        // Appends a note to the given message if the offending rule was created by a macro.

        return java.lang.String.format(
            "in %s attribute of %s rule %s: %s%s",
            maskInternalAttributeNames(attrName),
            ruleClassNameForLogging,
            getLabel(),
            message,
            getMacroMessageAppendix(attrName)
        )
    }

    /** Returns a string describing the macro that created this rule, or an empty string.  */
    protected abstract fun getMacroMessageAppendix(attrName: String?): String?

    protected abstract fun getLabel(): Label?

    protected abstract fun getConfiguration(): BuildConfigurationValue?

    protected abstract fun getRuleLocation(): net.starlark.java.syntax.Location?
}
