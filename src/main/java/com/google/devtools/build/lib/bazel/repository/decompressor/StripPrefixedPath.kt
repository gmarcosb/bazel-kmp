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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Utility class for removing a prefix from an archive's path.
 */
@ThreadSafety.Immutable
class StripPrefixedPath private constructor(
  @kotlin.jvm.JvmField val pathFragment: PathFragment?,
  private val found: Boolean,
  private val skip: Boolean
) {
    fun foundPrefix(): Boolean {
        return found
    }

    fun skip(): Boolean {
        return skip
    }

    companion object {
        /**
         * If a prefix is given, it will be removed from the entry's path. This also turns absolute paths
         * into relative paths (e.g., /usr/bin/bash will become usr/bin/bash, same as unzip's default
         * behavior) and normalizes the paths (foo/../bar////baz will become bar/baz). Note that this
         * could cause collisions, if a zip file had one entry for bin/some-binary and another entry for
         * /bin/some-binary.
         * 
         * 
         * Note that the prefix is stripped to move the files up one level, so if you have an entry
         * "foo/../bar" and a prefix of "foo", the result will be "bar" not "../bar".
         */
        fun maybeDeprefix(entry: ByteArray?, prefix: Optional<String?>): StripPrefixedPath {
            Preconditions.checkNotNull<ByteArray?>(entry)
            var entryPath: PathFragment = Companion.relativize(entry!!)
            if (prefix.isEmpty()) {
                return StripPrefixedPath(entryPath, false, false)
            }

            // Bazel parses Starlark files, which are the ultimate source of prefixes, as Latin-1
            // (ISO-8859-1).
            val prefixPath: PathFragment = relativize(prefix.get().getBytes(StandardCharsets.ISO_8859_1))
            var found = false
            var skip = false
            if (entryPath.startsWith(prefixPath)) {
                found = true
                entryPath = entryPath.relativeTo(prefixPath)
                if (entryPath.getPathString().isEmpty()) {
                    skip = true
                }
            } else {
                skip = true
            }
            return StripPrefixedPath(entryPath, found, skip)
        }

        /**
         * Normalize the path and, if it is absolute, make it relative (e.g., /foo/bar becomes foo/bar).
         */
        private fun relativize(path: ByteArray): PathFragment {
            var entryPath: PathFragment = createPathFragment(path)
            if (entryPath.isAbsolute()) {
                entryPath = entryPath.toRelative()
            }
            return entryPath
        }

        fun maybeDeprefixSymlink(
            rawTarget: ByteArray, prefix: Optional<String?>, root: Path
        ): PathFragment? {
            val wasAbsolute: Boolean = createPathFragment(rawTarget).isAbsolute()
            // Strip the prefix from the link path if set.
            val linkPathFragment: PathFragment? =
                maybeDeprefix(rawTarget, prefix).pathFragment
            if (wasAbsolute) {
                // Recover the path to an absolute path as maybeDeprefix() relativize the path
                // even if the prefix is not set
                return root.getRelative(linkPathFragment).asFragment()
            }
            return linkPathFragment
        }

        fun createPathFragment(rawBytes: ByteArray): PathFragment {
            // Bazel internally represents paths as raw bytes by using the Latin-1 encoding, which has the
            // property that (new String(bytes, ISO_8859_1)).getBytes(ISO_8859_1)) equals bytes for every
            // byte array bytes.
            return PathFragment.create(String(rawBytes, StandardCharsets.ISO_8859_1))
        }
    }
}
