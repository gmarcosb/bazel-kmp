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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory

/** Starlark namespace for creating build settings.  */
class StarlarkConfig : StarlarkConfigApi {
    override fun intSetting(flag: Boolean): BuildSetting {
        return BuildSetting.create(flag, com.google.devtools.build.lib.packages.Type.INTEGER)
    }

    override fun boolSetting(flag: Boolean): BuildSetting {
        return BuildSetting.create(flag, com.google.devtools.build.lib.packages.Type.BOOLEAN)
    }

    override fun stringSetting(flag: Boolean, allowMultiple: Boolean): BuildSetting {
        return BuildSetting.create(flag, com.google.devtools.build.lib.packages.Type.STRING, allowMultiple, false)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun stringListSetting(flag: Boolean, repeatable: Boolean): BuildSetting {
        if (repeatable && !flag) {
            throw net.starlark.java.eval.Starlark.errorf("'repeatable' can only be set for a setting with 'flag = True'")
        }
        return BuildSetting.create(flag, com.google.devtools.build.lib.packages.Types.STRING_LIST, false, repeatable)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun stringSetSetting(flag: Boolean, repeatable: Boolean): BuildSetting {
        if (repeatable && !flag) {
            throw net.starlark.java.eval.Starlark.errorf("'repeatable' can only be set for a setting with 'flag = True'")
        }
        return BuildSetting.create(flag, com.google.devtools.build.lib.packages.Types.STRING_SET, false, repeatable)
    }

    override fun exec(execGroupUnchecked: Any?): ExecutionTransitionFactory {
        return if (execGroupUnchecked === net.starlark.java.eval.Starlark.NONE)
            ExecutionTransitionFactory.createFactory()
        else
            ExecutionTransitionFactory.createFactory(execGroupUnchecked as String?)
    }

    override fun target(): ConfigurationTransitionApi? {
        return NoTransition.getFactory() as ConfigurationTransitionApi?
    }

    override fun none(): ConfigurationTransitionApi? {
        return NoConfigTransition.getFactory() as ConfigurationTransitionApi?
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<config>")
    }
}
