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
package com.google.devtools.build.lib.remote

import build.bazel.semver.SemVer

/** Represents a version of the Remote Execution API.  */
class ApiVersion(val major: Int, val minor: Int, val patch: Int, val prerelease: String) : Comparable<ApiVersion?> {
    constructor(semver: SemVer) : this(semver.getMajor(), semver.getMinor(), semver.getPatch(), semver.getPrerelease())

    override fun toString(): String {
        if (!prerelease.isEmpty()) {
            return prerelease
        }
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        builder.append(major)
        builder.append(".")
        builder.append(minor)
        if (patch != 0) {
            builder.append(".")
            builder.append(patch)
        }
        return builder.toString()
    }

    fun toSemVer(): SemVer {
        return SemVer.newBuilder()
            .setMajor(major)
            .setMinor(minor)
            .setPatch(patch)
            .setPrerelease(prerelease)
            .build()
    }

    /**
     * Compares the current API version to another API version.
     * 
     * @param other the API version to compare to.
     * @return 0 if the API versions are equal, a number less than 0 if the current version is earlier
     * than other, and a number greater than 0 if the current version is later than other. It is
     * assumed that all prerelease versions are earlier than all released versions.
     */
    override fun compareTo(other: ApiVersion): Int {
        if (!prerelease.isEmpty()) {
            if (other.prerelease.isEmpty()) {
                return -1
            }
            return prerelease.compareTo(other.prerelease)
        }
        if (!other.prerelease.isEmpty()) {
            return 1
        }
        if (major != other.major) {
            return java.lang.Integer.compare(major, other.major)
        }
        if (minor != other.minor) {
            return java.lang.Integer.compare(minor, other.minor)
        }
        return java.lang.Integer.compare(patch, other.patch)
    }

    companion object {
        // The version of the Remote Execution API that Bazel supports initially.
        val twoPointZero: ApiVersion = ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(0).build())

        // The version of the Remote Execution API that starts supporting the
        // Command.output_paths and ActionResult.output_symlinks fields.
        val twoPointOne: ApiVersion = ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(1).build())

        // The latest version of the Remote Execution API that Bazel is compatible with.
        val twoPointEleven: ApiVersion = ApiVersion(SemVer.newBuilder().setMajor(2).setMinor(11).build())

        // The current lowest/highest versions (inclusive) of the Remote Execution API that Bazel
        // supports. These fields will need to be updated together with all version changes.
        val low: ApiVersion = twoPointZero
        val high: ApiVersion = twoPointEleven
    }
}
