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
package com.google.devtools.common.options

/**
 * Contains metadata describing the origin of an option. This includes its priority, a message about
 * where it came from, and whether it was set explicitly or expanded/implied by other flags.
 */
class OptionInstanceOrigin(
    priority: com.google.devtools.common.options.OptionPriority?,
    source: String?,
    implicitDependent: com.google.devtools.common.options.ParsedOptionDescription?,
    expandedFrom: com.google.devtools.common.options.ParsedOptionDescription?
) {
    private val priority: com.google.devtools.common.options.OptionPriority?
    private val source: String?
    private val implicitDependent: com.google.devtools.common.options.ParsedOptionDescription?
    private val expandedFrom: com.google.devtools.common.options.ParsedOptionDescription?

    init {
        this.priority = priority
        this.source = source
        this.implicitDependent = implicitDependent
        this.expandedFrom = expandedFrom
    }

    fun getPriority(): com.google.devtools.common.options.OptionPriority? {
        return priority
    }

    fun getSource(): String? {
        return source
    }

    fun getImplicitDependent(): com.google.devtools.common.options.ParsedOptionDescription? {
        return implicitDependent
    }

    fun getExpandedFrom(): com.google.devtools.common.options.ParsedOptionDescription? {
        return expandedFrom
    }
}
