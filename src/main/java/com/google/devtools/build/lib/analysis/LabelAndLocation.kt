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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/**
 * Container for some attributes of a [Target] that is significantly less heavyweight than an
 * actual [Target] for purposes of serialization. Should still not be used indiscriminately,
 * since [Location] can be quite heavy on its own and each of these wrapper objects costs 24
 * bytes over an existing [Target].
 */
@AutoCodec
class LabelAndLocation(
    label: com.google.devtools.build.lib.cmdline.Label?,
    location: net.starlark.java.syntax.Location?
) {
    val label: com.google.devtools.build.lib.cmdline.Label?
    val location: net.starlark.java.syntax.Location?

    init {
        this.location = location
        this.label = label
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.cmdline.Label?>(label, "label")
        java.util.Objects.requireNonNull<net.starlark.java.syntax.Location?>(location, "location")
    }

    companion object {
        fun of(target: com.google.devtools.build.lib.packages.Target): LabelAndLocation {
            return LabelAndLocation(target.getLabel(), target.getLocation())
        }
    }
}
