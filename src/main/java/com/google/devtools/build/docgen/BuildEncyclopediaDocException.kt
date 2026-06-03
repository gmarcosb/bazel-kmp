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
package com.google.devtools.build.docgen

/** An exception in Build Encyclopedia generation.  */
class BuildEncyclopediaDocException : java.lang.Exception {
    /** Returns the location (filename or label, possibly with a line number) of the error.  */
    val location: String?

    /** Returns the error message text.  */
    val errorMsg: String?

    internal constructor(location: String?, errorMsg: String?) {
        this.location = location
        this.errorMsg = errorMsg
    }

    internal constructor(file: String?, lineNumber: Int, errorMsg: String?) {
        this.location = formatLocation(file, lineNumber)
        this.errorMsg = errorMsg
    }

    val message: String?
        get() = String.format("Error in %s: %s", location, errorMsg)

    companion object {
        fun formatLocation(file: String?, lineNumber: Int): String? {
            return String.format("%s:%d", file, lineNumber)
        }
    }
}
