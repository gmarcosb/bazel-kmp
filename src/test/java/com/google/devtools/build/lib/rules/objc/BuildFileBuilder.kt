// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.objc

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.testutil.Scratch
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import java.lang.String
import kotlin.Array
import kotlin.Boolean
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableList

internal class BuildFileBuilder {
    private class Version(var name: String?, var version: String?, vararg aliases: String?) {
        var aliases: Array<String?>

        init {
            this.aliases = aliases
        }
    }

    private val allVersions = HashMap<String?, Version>()
    private val localVersions: MutableList<Version> = ArrayList<Version>()
    private val remoteVersions: MutableList<Version> = ArrayList<Version>()
    private val explicitVersions: MutableList<Version> = ArrayList<Version>()

    private var localDefaultLabel: String? = null
    private var remoteDefaultLabel: String? = null
    private var explicitDefaultLabel: String? = null

    /**
     * Registers a new local version.
     * 
     * 
     * Only one local version may set `isDefault` to true.
     * 
     * @param name the name of the version
     * @param versionNumber the version number
     * @param isDefault whether this version is the local default
     * @param aliases the aliases for this version
     */
    @CanIgnoreReturnValue
    fun addLocalVersion(
        name: String?, versionNumber: String?, isDefault: Boolean, vararg aliases: String?
    ): BuildFileBuilder {
        val version = Version(name, versionNumber, *aliases)
        allVersions.put(name, version)
        localVersions.add(version)
        if (isDefault) {
            Preconditions.checkState(localDefaultLabel == null, "Only one local version may set 'isDefault=true'")
            localDefaultLabel = name
        }
        return this
    }

    /**
     * Registers a new remote version.
     * 
     * 
     * Only one remote version may set `isDefault` to true.
     * 
     * @param name the name of the version
     * @param versionNumber the version number
     * @param isDefault whether this version is the remote default
     * @param aliases the aliases for this version
     */
    @CanIgnoreReturnValue
    fun addRemoteVersion(
        name: String?, versionNumber: String?, isDefault: Boolean, vararg aliases: String?
    ): BuildFileBuilder {
        val version = Version(name, versionNumber, *aliases)
        allVersions.put(name, version)
        remoteVersions.add(version)
        if (isDefault) {
            Preconditions.checkState(remoteDefaultLabel == null, "Only one remote version may set 'isDefault=true'")
            remoteDefaultLabel = name
        }
        return this
    }

    /**
     * Registers a new explicit version.
     * 
     * 
     * Only one explicit version may set `isDefault` to true.
     * 
     * @param name the name of the version
     * @param versionNumber the version number
     * @param isDefault whether this version is the default
     * @param aliases the aliases for this version
     */
    @CanIgnoreReturnValue
    fun addExplicitVersion(
        name: String?, versionNumber: String?, isDefault: Boolean, vararg aliases: String?
    ): BuildFileBuilder {
        val version = Version(name, versionNumber, *aliases)
        allVersions.put(name, version)
        explicitVersions.add(version)
        if (isDefault) {
            Preconditions.checkState(
                explicitDefaultLabel == null, "Only one explicit version may set 'isDefault=true'"
            )
            explicitDefaultLabel = name
        }
        return this
    }

    private fun writeAllAvailableXcodes(lines: MutableList<String?>) {
        if (!localVersions.isEmpty()) {
            Preconditions.checkNotNull<String?>(localDefaultLabel, "One local version must be labeled as the default")
            writeAvailableXcodes("local", localDefaultLabel, localVersions, lines)
        }
        if (!remoteVersions.isEmpty()) {
            Preconditions.checkNotNull<String?>(remoteDefaultLabel, "One remote version must be labeled as the default")
            writeAvailableXcodes("remote", remoteDefaultLabel, remoteVersions, lines)
        }
    }

    private fun writeStandardXcodeConfig(lines: MutableList<String?>) {
        if (!explicitVersions.isEmpty()) {
            Preconditions.checkNotNull<String?>(
                explicitDefaultLabel, "'default' is a required field for the 'xcode_config' rule"
            )
            lines.add("xcode_config(")
            lines.add("    name = 'foo',")
            lines.add(String.format("    default = '%s',", explicitDefaultLabel))
            lines.add(String.format("    versions = %s,", formatVersionNames(explicitVersions)))
            lines.add(")")
        }
    }

    @Throws(IOException::class)
    fun write(scratch: Scratch, filename: String?) {
        val lines: MutableList<String?> = ArrayList<String?>()
        lines.add("load('@build_bazel_apple_support//xcode:xcode_version.bzl', 'xcode_version')")
        lines.add("load('@build_bazel_apple_support//xcode:available_xcodes.bzl', 'available_xcodes')")
        lines.add("load('@build_bazel_apple_support//xcode:xcode_config.bzl', 'xcode_config')")
        for (version in allVersions.values) {
            writeVersion(version, lines)
        }

        if (!localVersions.isEmpty() && !remoteVersions.isEmpty()) {
            writeAllAvailableXcodes(lines)
            writeLocalRemoteXcodeConfig(lines)
        }
        if (!explicitVersions.isEmpty()) {
            writeStandardXcodeConfig(lines)
        }
        scratch.file(filename, *lines.toTypedArray<String?>())
    }

    companion object {
        private fun writeVersion(version: Version, lines: MutableList<String?>) {
            lines.add("xcode_version(")
            lines.add(String.format("    name = '%s',", version.name))
            lines.add(String.format("    version = '%s',", version.version))
            if (version.aliases.size != 0) {
                lines.add(String.format("    aliases = ['%s'],", String.join("', '", *version.aliases)))
            }
            lines.add(")")
        }

        private fun formatVersionNames(versions: MutableList<Version>): kotlin.String {
            var versionNames = ""
            for (version in versions) {
                versionNames += kotlin.String.format("':%s', ", version.name)
            }
            return "[" + versionNames + "]"
        }

        private fun writeAvailableXcodes(
            name: kotlin.String?,
            defaultVersion: kotlin.String?,
            versions: MutableList<Version>,
            lines: MutableList<kotlin.String?>
        ) {
            lines.add("available_xcodes(")
            lines.add(kotlin.String.format("    name = '%s',", name))
            lines.add(kotlin.String.format("    default = ':%s',", defaultVersion))
            lines.add(kotlin.String.format("    versions = %s,", formatVersionNames(versions)))
            lines.add(")")
        }

        private fun writeLocalRemoteXcodeConfig(lines: MutableList<kotlin.String?>) {
            lines.add("xcode_config(")
            lines.add("    name = 'foo',")
            lines.add("    local_versions = 'local',")
            lines.add("    remote_versions = 'remote',")
            lines.add(")")
        }
    }
}
