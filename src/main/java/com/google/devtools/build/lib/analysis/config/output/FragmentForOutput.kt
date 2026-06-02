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

/**
 * Data structure defining a [com.google.devtools.build.lib.analysis.config.Fragment] for the
 * purpose of creating user output.
 * 
 * 
 * [com.google.devtools.build.lib.analysis.config.Fragment] is a Java object representation
 * of a domain-specific "piece" of configuration (like "C++-related configuration"). It depends on
 * one or more [com.google.devtools.build.lib.analysis.config.FragmentOptions], which are the
 * `--flag=value` pairs that key configurations.
 * 
 * 
 * See [FragmentOptionsForOutput] and [ConfigurationForOutput] for further details.
 */
class FragmentForOutput(
    @kotlin.jvm.JvmField private val name: String, // We store the name of the associated FragmentOptions instead of FragmentOptionsForOutput
    // objects because multiple fragments may use the same FragmentOptions and we don't want to list
    // it multiple times.
    @kotlin.jvm.JvmField private val fragmentOptions: MutableList<String?>
) {
    init {
        this.fragmentOptions = fragmentOptions
    }

    fun getName(): String {
        return name
    }

    /** The names of the FragmentOptions, sorted.  */
    fun getFragmentOptions(): MutableList<String?> {
        return fragmentOptions
    }

    override fun equals(o: Any?): Boolean {
        if (o is FragmentForOutput) {
            return o.name == name && o.fragmentOptions == fragmentOptions
        }
        return false
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(name, fragmentOptions)
    }
}
