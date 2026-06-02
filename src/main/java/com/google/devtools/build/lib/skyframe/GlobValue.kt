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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * A value corresponding to a glob. It has two subclasses, [GlobValueWithNestedSet] and [ ].
 */
abstract class GlobValue : SkyValue {
    /** Returns all glob matching [PathFragment]s in [ImmutableSet].  */
    @kotlin.jvm.JvmField
    abstract val matches: com.google.common.collect.ImmutableSet<PathFragment?>?

    companion object {
        /**
         * Constructs a [GlobDescriptor] for a glob lookup. `packageName` is assumed to be an
         * existing package. Trying to glob into a non-package is undefined behavior.
         * 
         * @throws InvalidGlobPatternException if the pattern is not valid.
         */
        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
        @Throws(InvalidGlobPatternException::class)
        fun key(
            packageId: PackageIdentifier?,
            packageRoot: Root?,
            pattern: String,
            globOperation: Globber.Operation?,
            subdir: PathFragment?
        ): GlobDescriptor? {
            if (pattern.indexOf('?') != -1) {
                throw InvalidGlobPatternException(pattern, "wildcard ? forbidden")
            }

            val error: String? = UnixGlob.checkPatternForError(pattern)
            if (error != null) {
                throw InvalidGlobPatternException(pattern, error)
            }

            return internalKey(packageId, packageRoot, subdir, pattern, globOperation)
        }

        /**
         * Constructs a [GlobDescriptor] for a glob lookup.
         * 
         * 
         * Do not use outside `GlobFunction`.
         */
        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
        fun internalKey(
            packageId: PackageIdentifier?,
            packageRoot: Root?,
            subdir: PathFragment?,
            pattern: String?,
            globOperation: Globber.Operation?
        ): GlobDescriptor? {
            return GlobDescriptor.Companion.create(packageId, packageRoot, subdir, pattern, globOperation)
        }
    }
}
