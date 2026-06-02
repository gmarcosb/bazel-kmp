// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.common.base.Supplier
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** Info item for the make environment.  */
class MakeInfoItem(name: String?, description: String?) : InfoItem(name, description, false) {
    val description: String
        get() = "Make environment variable '" + name + "'"

    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
    ): ByteArray {
        return print(description)
    }
}
