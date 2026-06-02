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
package net.starlark.java.eval

/** [GuardedValue] that controls access based on an experimental or incompatible flag.  */
object FlagGuardedValue {
    /**
     * Creates a flag guard which only permits access of the given object when the given boolean flag
     * is true. If the given flag is false and the object would be accessed, an error is thrown
     * describing the feature as experimental, and describing that the flag must be set to true.
     * 
     * 
     * The flag identifier must have a + or - prefix; see StarlarkSemantics.
     */
    @kotlin.jvm.JvmStatic
    fun onlyWhenExperimentalFlagIsTrue(flag: String, obj: Any): net.starlark.java.eval.GuardedValue {
        require(!(flag.charAt(0) != '-' && flag.charAt(0) != '+')) {
            java.lang.String.format(
                "flag needs [+-] prefix: %s",
                flag
            )
        }
        return object : net.starlark.java.eval.GuardedValue {
            override fun getObject(): Any {
                return obj
            }

            override fun getErrorFromAttemptingAccess(name: String?): String {
                return (name
                        + " is experimental and thus unavailable with the current flags. It may be enabled by"
                        + " setting --"
                        + flag.substring(1))
            }

            override fun isObjectAccessibleUsingSemantics(
                semantics: net.starlark.java.eval.StarlarkSemantics, clientData: Any?
            ): Boolean {
                return semantics.isFeatureEnabledBasedOnTogglingFlags(flag, "")
            }
        }
    }

    /**
     * Creates a flag guard which only permits access of the given object when the given boolean flag
     * is false. If the given flag is true and the object would be accessed, an error is thrown
     * describing the feature as deprecated, and describing that the flag must be set to false.
     * 
     * 
     * The flag identifier must have a + or - prefix; see StarlarkSemantics.
     */
    @kotlin.jvm.JvmStatic
    fun onlyWhenIncompatibleFlagIsFalse(flag: String, obj: Any): net.starlark.java.eval.GuardedValue {
        require(!(flag.charAt(0) != '-' && flag.charAt(0) != '+')) {
            java.lang.String.format(
                "flag needs [+-] prefix: %s",
                flag
            )
        }
        return object : net.starlark.java.eval.GuardedValue {
            override fun getObject(): Any {
                return obj
            }

            override fun getErrorFromAttemptingAccess(name: String?): String {
                return (name
                        + " is deprecated and will be removed soon. It may be temporarily re-enabled by"
                        + " setting --"
                        + flag.substring(1)
                        + "=false")
            }

            override fun isObjectAccessibleUsingSemantics(
                semantics: net.starlark.java.eval.StarlarkSemantics, clientData: Any?
            ): Boolean {
                return semantics.isFeatureEnabledBasedOnTogglingFlags("", flag)
            }
        }
    }
}
