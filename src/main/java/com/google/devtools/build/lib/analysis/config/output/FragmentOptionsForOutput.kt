// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config.output

import java.util.SortedMap

/**
 * Data structure defining a [com.google.devtools.build.lib.analysis.config.FragmentOptions]
 * for creating user output.
 * 
 * 
 * See [FragmentForOutput] and [ConfigurationForOutput] for further details.
 */
class FragmentOptionsForOutput(name: String, options: SortedMap<String?, String?>) {
    @kotlin.jvm.JvmField
    private val name: String
    private val options: SortedMap<String?, String?>

    init {
        this.name = name
        this.options = options
    }

    fun getName(): String {
        return name
    }

    fun getOptions(): SortedMap<String?, String?> {
        return options
    }

    fun optionNames(): MutableSet<String?> {
        return this.options.keys
    }

    fun getOption(optionName: String?): String? {
        return this.options.get(optionName)
    }

    override fun equals(o: Any?): Boolean {
        if (o is FragmentOptionsForOutput) {
            return o.name == name && o.options == options
        }
        return false
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(name, options)
    }
}
