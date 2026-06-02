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
package com.google.devtools.build.lib.profiler.memory

import com.google.devtools.build.lib.packages.AspectClass
import com.google.devtools.build.lib.packages.RuleClass

/** Thread-local variables that keep track of the current rule being configured.  */
object CurrentRuleTracker {
    private val currentRule: java.lang.ThreadLocal<RuleClass?> = java.lang.ThreadLocal<RuleClass?>()
    private val currentAspect: java.lang.ThreadLocal<AspectClass?> = java.lang.ThreadLocal<AspectClass?>()
    private var enabled = false

    @kotlin.jvm.JvmStatic
    fun setEnabled(enabled: Boolean) {
        CurrentRuleTracker.enabled = enabled
    }

    /**
     * Sets the current rule being instantiated. Used for memory tracking.
     * 
     * 
     * You must call [CurrentRuleTracker.endConfiguredTarget] after calling this.
     */
    fun beginConfiguredTarget(ruleClass: RuleClass?) {
        if (!enabled) {
            return
        }
        currentRule.set(ruleClass)
    }

    @kotlin.jvm.JvmStatic
    fun endConfiguredTarget() {
        if (!enabled) {
            return
        }
        currentRule.set(null)
    }

    /**
     * Sets the current aspect being instantiated. Used for memory tracking.
     * 
     * 
     * You must call [CurrentRuleTracker.endConfiguredAspect] after calling this.
     */
    fun beginConfiguredAspect(aspectClass: AspectClass?) {
        if (!enabled) {
            return
        }
        currentAspect.set(aspectClass)
    }

    @kotlin.jvm.JvmStatic
    fun endConfiguredAspect() {
        if (!enabled) {
            return
        }
        currentAspect.set(null)
    }

    fun getRule(): RuleClass? {
        com.google.common.base.Preconditions.checkState(enabled)
        return currentRule.get()
    }

    fun getAspect(): AspectClass? {
        com.google.common.base.Preconditions.checkState(enabled)
        return currentAspect.get()
    }
}
