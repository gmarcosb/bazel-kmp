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

import com.google.common.base.Preconditions
import com.google.common.base.Supplier
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** Info item for the {blaze,bazel}-bin directory.  */
class BlazeBinInfoItem(productName: String?) :
    InfoItem(productName + "-bin", "Configuration dependent directory for binaries.", false) {
    // This is one of the three (non-hidden) info items that require a configuration, because the
    // corresponding paths contain the short name. Maybe we should recommend using the symlinks
    // or make them hidden by default?
    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?, env: CommandEnvironment?
    ): ByteArray {
        Preconditions.checkNotNull<Supplier<BuildConfigurationValue?>?>(configurationSupplier)
        return print(configurationSupplier!!.get().getBinDirectory(RepositoryName.MAIN).getRoot())
    }
}
