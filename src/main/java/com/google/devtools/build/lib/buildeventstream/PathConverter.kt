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
package com.google.devtools.build.lib.buildeventstream

import java.net.URISyntaxException

/**
 * Interface for conversion of paths to URIs.
 */
interface PathConverter {
    /** A [PathConverter] that returns a path formatted as a URI with a `file` scheme.  */ // TODO(ulfjack): Make this a static final field.
    class FileUriPathConverter : PathConverter {
        override fun apply(path: com.google.devtools.build.lib.vfs.Path?): String? {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(path)
            return pathToUriString(path.getPathString())
        }

        companion object {
            /**
             * Returns the path encoded as an [URI].
             * 
             * 
             * This concrete implementation returns URIs with "file" as the scheme. For Example: - On
             * Unix the path "/tmp/foo bar.txt" will be encoded as "file:///tmp/foo%20bar.txt". - On Windows
             * the path "C:\Temp\Foo Bar.txt" will be encoded as "file:///C:/Temp/Foo%20Bar.txt"
             * 
             * 
             * Implementors extending this class for special filesystems will likely need to override
             * this method.
             */
            @kotlin.jvm.JvmStatic
            @com.google.common.annotations.VisibleForTesting
            fun pathToUriString(path: String): String? {
                var path = path
                if (!path.startsWith("/")) {
                    // On Windows URI's need to start with a '/'. i.e. C:\Foo\Bar would be file:///C:/Foo/Bar
                    path = "/" + path
                }
                try {
                    return java.net.URI(
                        "file",  // Needs to be "" instead of null, so that toString() will append "//" after the
                        // scheme.
                        // We need this for backwards compatibility reasons as some consumers of the BEP are
                        // broken.
                        "",
                        path,
                        null,
                        null
                    )
                        .toString()
                } catch (e: URISyntaxException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
        }
    }

    /**
     * Return the URI corresponding to the given path.
     * 
     * 
     * This method may return null, in which case the associated [BuildEventArtifactUploader]
     * was permanently unable to upload the file. The file should be omitted from the BEP stream.
     * 
     * 
     * This method may throw [IllegalStateException] if it is passed a path that
     * wasn't declared in [BuildEvent.referencedLocalFiles].
     */
    fun apply(path: com.google.devtools.build.lib.vfs.Path?): String?

    companion object {
        /** An implementation that throws on every call to [.apply].  */
        @kotlin.jvm.JvmField
        val NO_CONVERSION: PathConverter =
            com.google.devtools.build.lib.buildeventstream.PathConverter { path: com.google.devtools.build.lib.vfs.Path? ->
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Can't convert '%s', as it has not been declared as a referenced artifact of a"
                                + " build event",
                        path.getPathString()
                    )
                )
            }
    }
}
