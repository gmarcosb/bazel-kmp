// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoBuilder

/**
 * Set of classes for RuleClass to use to track how an Allowlist must be checked.
 * 
 * 
 * This class is used at both loading time in packages an thus cannot actually refer to [ ]. These are processed in [RuleConfiguredTargetBuilder].
 * 
 * @param allowlistAttr Return attribute name containing the allowlist to check against.
 * @param errorMessage Return error message to print if allowlist check fails.
 * @param locationCheck Return what rule location to check against allowlist.
 * @param attributeSetTrigger If non-null, check that the attribute is explicitly set before
 * checking allowlist.
 */
@kotlin.jvm.JvmRecord
data class AllowlistChecker(
    val allowlistAttr: String?,
    val errorMessage: String?,
    val locationCheck: LocationCheck?,
    val attributeSetTrigger: String?
) {
    /** Track whether checking rule instance or rule definition location  */
    enum class LocationCheck {
        INSTANCE,  // pass if rule instance in allowlist
        DEFINITION,  // pass if rule definition in allowlist
        INSTANCE_OR_DEFINITION // pass if either in allowlist
    }

    /** Standard builder class.  */
    @AutoBuilder
    abstract class Builder {
        abstract fun setAllowlistAttr(allowlistAttr: String?): Builder?

        abstract fun setErrorMessage(errorMessage: String?): Builder?

        abstract fun setAttributeSetTrigger(attributeSetTrigger: String?): Builder?

        abstract fun setLocationCheck(locationCheck: LocationCheck?): Builder?

        abstract fun build(): AllowlistChecker?
    }

    init {
        java.util.Objects.requireNonNull<String?>(allowlistAttr, "allowlistAttr")
        java.util.Objects.requireNonNull<String?>(errorMessage, "errorMessage")
        java.util.Objects.requireNonNull<LocationCheck?>(locationCheck, "locationCheck")
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_AllowlistChecker_Builder()
        }
    }
}
