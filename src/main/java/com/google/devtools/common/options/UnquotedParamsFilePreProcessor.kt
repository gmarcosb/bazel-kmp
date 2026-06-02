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
package com.google.devtools.common.options

import java.io.IOException

/**
 * A [ParamsFilePreProcessor] that processes a parameter file using the `com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType.UNQUOTED` format. This
 * format assumes each parameter is on a separate line and does not perform any special handling on
 * non-newline whitespace or special characters.
 */
class UnquotedParamsFilePreProcessor(fs: java.nio.file.FileSystem?) :
    com.google.devtools.common.options.ParamsFilePreProcessor(fs) {
    @Throws(IOException::class)
    override fun parse(paramsFile: java.nio.file.Path): MutableList<String?> {
        return java.nio.file.Files.readAllLines(paramsFile, java.nio.charset.StandardCharsets.UTF_8)
    }
}
