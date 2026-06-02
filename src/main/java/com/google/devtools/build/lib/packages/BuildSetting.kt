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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.Type.LabelClass
import com.google.devtools.build.lib.starlarkbuildapi.config.StarlarkConfigApi.BuildSettingApi

/**
 * Metadata of a build setting rule's properties. This describes the build setting's type (for
 * example, 'int' or 'string'), and whether the build setting corresponds to a command line flag.
 */
class BuildSetting private constructor(
    @kotlin.jvm.JvmField private val isFlag: Boolean,
    type: com.google.devtools.build.lib.packages.Type<*>?,
    allowMultiple: Boolean,
    repeatable: Boolean
) : BuildSettingApi {
    private val type: com.google.devtools.build.lib.packages.Type<*>?
    private val allowMultiple: Boolean
    @kotlin.jvm.JvmField
    private val repeatable: Boolean

    init {
        this.type = type
        this.allowMultiple = allowMultiple
        this.repeatable = repeatable
    }

    fun getType(): com.google.devtools.build.lib.packages.Type<*>? {
        return type
    }

    @com.google.common.annotations.VisibleForTesting
    fun isFlag(): Boolean {
        return isFlag
    }

    fun allowsMultiple(): Boolean {
        return allowMultiple
    }

    fun isRepeatableFlag(): Boolean {
        return repeatable
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<build_setting." + type + ">")
    }

    companion object {
        fun create(
            isFlag: Boolean,
            type: com.google.devtools.build.lib.packages.Type<*>?,
            allowMultiple: Boolean,
            repeatable: Boolean
        ): BuildSetting {
            return BuildSetting(isFlag, type, allowMultiple, repeatable)
        }

        fun create(isFlag: Boolean, type: com.google.devtools.build.lib.packages.Type<*>): BuildSetting {
            com.google.common.base.Preconditions.checkState(
                type.getLabelClass() != LabelClass.DEPENDENCY,
                "Build settings should not create a dependency with their default attribute"
            )
            return BuildSetting(isFlag, type,  /* allowMultiple= */false, false)
        }
    }
}
