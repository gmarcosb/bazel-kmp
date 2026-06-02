// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.objc

import com.google.common.annotations.VisibleForTesting
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo
import com.google.devtools.build.lib.rules.apple.DottedVersion
import net.starlark.java.eval.EvalException

/** A class that exposes apple rule implementation internals to Starlark.  */
class AppleStarlarkCommon

    : AppleCommonApi<ConstraintValueInfo?, StarlarkRuleContext?> {
    private var platform: StructImpl? = null

    val platformStruct: StructImpl?
        get() {
            if (platform == null) {
                platform = ApplePlatform.Companion.getStarlarkStruct()
            }
            return platform
        }

    @Throws(EvalException::class)
    override fun dottedVersion(version: String?): DottedVersion {
        try {
            return DottedVersion.Companion.fromString(version)
        } catch (e: InvalidDottedVersionException) {
            throw EvalException(e.getMessage())
        }
    }

    companion object {
        @VisibleForTesting
        const val BAD_KEY_ERROR: String = "Argument %s not a recognized key, 'strict_include', or 'providers'."

        @VisibleForTesting
        const val NOT_SET_ERROR: String = "Value for key %s must be a set, instead found %s."
    }
}
