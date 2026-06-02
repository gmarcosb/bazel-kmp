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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Exception indicating an attempt to access a target which is not found or does
 * not exist.
 */
class NoSuchTargetException private constructor(message: String?, label: Label?, hasTarget: Boolean) :
    NoSuchThingException(
        message,
        if (hasTarget) BuildFileContainsErrorsException(label.getPackageIdentifier()) else null
    ) {
    private val label: Label?
    private val hasTarget: Boolean

    constructor(message: String?) : this(
        message,  /*label=*/
        null,  /*hasTarget=*/
        false
    )

    constructor(label: Label?, message: String?) : this(
        "no such target '" + label + "': " + message,
        label,  /*hasTarget=*/
        false
    )

    constructor(targetInError: com.google.devtools.build.lib.packages.Target) : this(targetInError.getLabel())

    private constructor(label: Label?) : this(
        "Target '" + label + "' contains an error and its package is in error",
        label,  /* hasTarget= */
        true
    )

    init {
        // TODO(bazel-team): Does the exception matter?
        this.label = label
        this.hasTarget = hasTarget
    }

    fun getLabel(): Label? {
        return label
    }

    /** Return whether parsing completed enough to construct the target.  */
    fun hasTarget(): Boolean {
        return hasTarget
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        val uncheckedDetailedExitCode: DetailedExitCode? = getUncheckedDetailedExitCode()
        return if (uncheckedDetailedExitCode != null)
            uncheckedDetailedExitCode
        else
            defaultDetailedExitCode()
    }

    private fun defaultDetailedExitCode(): DetailedExitCode {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(com.google.common.base.Strings.nullToEmpty(getMessage()))
                .setPackageLoading(
                    PackageLoading.newBuilder().setCode(PackageLoading.Code.TARGET_MISSING).build()
                )
                .build()
        )
    }

    companion object {
        /**
         * This factory is used when [Target] was loaded but isn't available to the caller.
         * 
         * 
         * This is used when an error is bubbled up from a child to parent [ ] invocation.
         */
        fun createForParentPropagation(label: Label?): NoSuchTargetException {
            return NoSuchTargetException(label)
        }
    }
}
