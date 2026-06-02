// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.repository

import com.google.devtools.build.lib.packages.NoSuchPackageException

/** Exception thrown when something goes wrong accessing a rule.  */
class ExternalPackageException : SkyFunctionException {
    constructor(cause: NoSuchPackageException?, transience: Transience?) : super(cause, transience)

    /** Error reading or writing to the filesystem.  */
    constructor(cause: IOException?, transience: Transience?) : super(cause, transience)

    /** For errors in WORKSPACE file rules (e.g., malformed paths or URLs).  */
    constructor(cause: net.starlark.java.eval.EvalException?, transience: Transience?) : super(cause, transience)
}