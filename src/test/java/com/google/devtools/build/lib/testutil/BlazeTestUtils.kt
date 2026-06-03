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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.vfs.FileSystemUtils

/**
 * Some static utility functions for testing Blaze code. In contrast to [TestUtils], these
 * functions are Blaze-specific.
 */
object BlazeTestUtils {
    /**
     * Writes a FilesetRule to a String array.
     * 
     * @param name the name of the rule.
     * @param out the output directory.
     * @param entries The FilesetEntry entries.
     * @return the String array of the rule.  One String for each line.
     */
    fun createFilesetRule(name: String?, out: String?, vararg entries: String?): Array<String?> {
        return arrayOf<String?>(
            String.format("Fileset(name = '%s', out = '%s',", name, out),
            "        entries = [" + com.google.common.base.Joiner.on(", ").join(entries) + "])"
        )
    }

    fun undeclaredOutputDir(): java.io.File {
        val dir: String? = java.lang.System.getenv("TEST_UNDECLARED_OUTPUTS_DIR")
        if (dir != null) {
            return java.io.File(dir)
        }

        return com.google.devtools.build.lib.testutil.TestUtils.tmpDirFile()
    }

    fun runfilesDir(): String? {
        val runfilesDirStr: String = com.google.devtools.build.lib.testutil.TestUtils.getUserValue("TEST_SRCDIR")
        com.google.common.base.Preconditions.checkState(
            runfilesDirStr != null && runfilesDirStr.length > 0,
            "TEST_SRCDIR unset or empty"
        )
        return java.io.File(runfilesDirStr).getAbsolutePath()
    }

    /** Creates an empty file, along with all its parent directories.  */
    @Throws(IOException::class)
    fun makeEmptyFile(path: Path) {
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(path)
    }

    /**
     * Changes the mtime of the file "path", which must exist.  No guarantee is
     * made about the new mtime except that it is different from the previous one.
     * 
     * @throws IOException if the mtime could not be read or set.
     */
    @Throws(IOException::class)
    fun changeModtime(path: Path) {
        val prevMtime: Long = path.getLastModifiedTime()
        var newMtime = prevMtime
        do {
            newMtime += 1000
            path.setLastModifiedTime(newMtime)
        } while (path.getLastModifiedTime() === prevMtime)
    }

    /**
     * Creates a list of arguments to pass to Bazel, with flags necessary for Bazel to work properly
     * appended to the original `args` array.
     */
    fun makeArgs(vararg args: String?): java.util.ArrayList<String?> {
        val result: java.util.ArrayList<String?> =
            java.util.ArrayList<String?>(
                (args.size
                        + TestConstants.PRODUCT_SPECIFIC_FLAGS.size
                        + TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS.size)
            )
        Collections.addAll<String?>(result, *args)
        result.addAll(TestConstants.PRODUCT_SPECIFIC_FLAGS)
        result.addAll(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        return result
    }
}
