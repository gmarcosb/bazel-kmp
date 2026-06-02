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
package com.google.devtools.build.lib.rules.java

import com.google.common.base.Predicate
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.vfs.PathFragment

/** Utility methods for use by Java-related parts of the build system.  */
object JavaUtil {
    // ---------- Java related methods
    /*
   * TODO(bazel-team): (2009)
   *
   * This way of figuring out Java source roots is basically
   * broken. I think we need to support these two use cases:
   * (1) A user puts their shell into a directory named java.
   * (2) Someplace in the tree, there's a package named java.
   *
   * (1) is more important than (2); and (2) cannot always be guaranteed
   * due to sloppy implementations in the past; most notably the old
   * tools/boilerplate_rules.mk code for compiling Java.
   *
   * Basically, to implement correct semantics, we will need to configure
   * Java source roots based on the package path, plus some heuristics to
   * support legacy code, maybe.
   *
   * Roughly:
   * Given a path, find the source root that applies to it by
   * - walk over the elements in the package path
   * - add "java", "javatests" to them
   * - find the first element that is a maximal prefix to the Java file
   * - for experimental, some legacy support that basically has some
   * arbitrary padding before the Java sourceroot.
   */
    /** Java source roots, used to relativize resource paths and infer main_class values.  */
    val KNOWN_SOURCE_ROOTS: ImmutableSet<String?> = ImmutableSet.of<String?>("java", "javatests", "src", "testsrc")

    /**
     * Finds the index of the segment in a Java path fragment that precedes the source root. Starts
     * from the first "java" or "javatests" or "src" or "testsrc" segment. If the found item was
     * "src", check if this is followed by "main" or "test" and then "java" or "resources" (maven
     * layout). If the found item was "src", or "java"/"javatests" at the first segment, check for a
     * nested root directory (src, java or javatests). A nested root must be followed by
     * (com|net|org), or matching maven structure for nested "src", to be accepted, to avoid false
     * positives.
     * 
     * @param path a Java source dir or file path
     * @return the index of the java segment or -1 iff no java segment was found.
     */
    private fun javaSegmentIndex(path: PathFragment): Int {
        require(!path.isAbsolute()) { "path must not be absolute: '" + path + "'" }
        var rootIndex = Iterables.indexOf<String?>(
            path.segments(),
            Predicate { `object`: String? -> KNOWN_SOURCE_ROOTS.contains(`object`) })
        if (rootIndex == -1) {
            return -1
        }
        val isSrc = "src" == path.getSegment(rootIndex)
        var checkMavenIndex = if (isSrc) rootIndex else -1
        if (rootIndex == 0 || isSrc) {
            // Check for a nested root directory.
            var i = rootIndex + 1
            val max = path.segmentCount() - 2
            while (i <= max) {
                val segment = path.getSegment(i)
                if ("src" == segment
                    || (isSrc && ("javatests" == segment || "java" == segment))
                ) {
                    val next = path.getSegment(i + 1)
                    if ("com" == next || "org" == next || "net" == next) {
                        // Check for common first element of java package, to avoid false positives.
                        rootIndex = i
                    } else if ("src" == segment) {
                        // Also accept maven style src/(main|test)/(java|resources).
                        checkMavenIndex = i
                    }
                    break
                }
                i++
            }
        }
        // Check for (main|test)/(java|resources) after /src/.
        if (checkMavenIndex >= 0 && checkMavenIndex + 2 < path.segmentCount()) {
            var next = path.getSegment(checkMavenIndex + 1)
            if ("main" == next || "test" == next) {
                next = path.getSegment(checkMavenIndex + 2)
                if ("java" == next || "resources" == next) {
                    rootIndex = checkMavenIndex + 2
                }
            }
        }
        return rootIndex
    }

    /**
     * Given the PathFragment of a Java source file, returns the Java root relative path or 'null' if
     * no java root can be determined.
     * 
     * 
     * For example, "{workspace}/java/foo/bar/wiz" and "{workspace}/javatests/foo/bar/wiz" both
     * result in "foo/bar/wiz".
     * 
     * 
     * TODO(bazel-team): (2011) We need to have a more robust way to determine the Java root of a
     * relative path rather than simply trying to find the "java" or "javatests" directory.
     */
    @kotlin.jvm.JvmStatic
    fun getJavaPath(path: PathFragment): PathFragment? {
        val index = javaSegmentIndex(path)
        if (index >= 0) {
            return path.subFragment(index + 1)
        }
        return null
    }
}
