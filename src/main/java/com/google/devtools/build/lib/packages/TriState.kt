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
package com.google.devtools.build.lib.packages

/**
 * Enum used to represent tri-state parameters in rule attributes (yes/no/auto).
 */
enum class TriState {
    YES,
    NO,
    AUTO;

    fun toInt(): Int {
        return when (this) {
            com.google.devtools.build.lib.packages.TriState.YES -> 1
            com.google.devtools.build.lib.packages.TriState.NO -> 0
            com.google.devtools.build.lib.packages.TriState.AUTO -> -1
        }
    }

    companion object {
        fun fromInt(n: Int): TriState {
            return when (n) {
                1 -> com.google.devtools.build.lib.packages.TriState.YES
                0 -> com.google.devtools.build.lib.packages.TriState.NO
                -1 -> com.google.devtools.build.lib.packages.TriState.AUTO
                else -> throw java.lang.IllegalArgumentException("TriState must be -1, 0, or 1")
            }
        }
    }
}
