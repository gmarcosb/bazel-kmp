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

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue
import com.google.devtools.common.options.OptionsParsingResult

/**
 * Info item for the default package path. It is deprecated, it still works, when explicitly
 * requested, but are not shown by default. It prints multi-line messages and thus don't play well
 * with grep. We don't print them unless explicitly requested.
 */
@Deprecated("")
class DefaultPackagePathInfoItem(private val commandOptions: OptionsParsingResult?) :
    InfoItem("default-package-path", "The default package path", true) {
    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
    ): ByteArray {
        Preconditions.checkNotNull<OptionsParsingResult?>(commandOptions)
        return print(JOINER.join(commandOptions!!.getOptions<O?>(PackageOptions::class.java).getPackagePath()))
    }

    companion object {
        private val JOINER: Joiner = Joiner.on(":")
    }
}
