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
package com.google.devtools.build.buildjar

import com.google.auto.value.AutoValue
import java.nio.file.Path

/**
 * Holds information about the Bazel rule that created a certain jar.
 * 
 * 
 * Rules that use Aspects (http://bazel.build/rules/aspects) to compile jars will result in
 * 'aspect()' being populated.
 */
@AutoValue
abstract class JarOwner {
    abstract fun jar(): Path?

    abstract fun label(): java.util.Optional<String?>?

    abstract fun aspect(): java.util.Optional<String?>?

    fun withLabel(label: java.util.Optional<String?>?): JarOwner {
        return AutoValue_JarOwner(jar(), label, aspect())
    }

    companion object {
        fun create(jar: Path?): JarOwner {
            return AutoValue_JarOwner(jar, java.util.Optional.empty<T?>(), java.util.Optional.empty<T?>())
        }

        fun create(jar: Path?, label: String, aspect: java.util.Optional<String?>?): JarOwner {
            return AutoValue_JarOwner(jar, java.util.Optional.of<T?>(label), aspect)
        }
    }
}
