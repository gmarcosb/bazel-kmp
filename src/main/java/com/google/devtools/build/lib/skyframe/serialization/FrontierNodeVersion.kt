// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId

/** A tuple representing the version of a cached SkyValue in the frontier.  */
class FrontierNodeVersion(
    topLevelConfigChecksum: String,
    blazeInstallMD5: com.google.common.hash.HashCode,
    starlarkSemanticsFingerprint: ByteArray?,
    evaluatingVersion: IntVersion,
    distinguisherBytesForTesting: String,
    useFakeStampData: Boolean,
    clientId: java.util.Optional<ClientId?>?
) {
    /**
     * The checksum of the top-level configuration (trimmed of test options).
     * 
     * 
     * The configuration of any node in the build graph includes a mnemonic (e.g. k8-opt) that is
     * part of its output path. If a transition is applied, this mnemonic is computed based on the
     * delta between the node's configuration and the top-level configuration producing an ST-hash.
     * 
     * 
     * If the top-level configuration changes, the output paths of artifacts may change even if the
     * node's configuration remains distinct and identical (e.g. in a transition). Including this
     * checksum ensures that we do not reuse nodes that would produce artifacts at incorrect paths
     * relative to the current build's top-level configuration.
     * 
     * 
     * See b/360073915.
     */
    val topLevelConfigChecksum: String?

    val topLevelConfigFingerprint: ByteArray

    /**
     * The MD5 hash of the Bazel installation.
     * 
     * 
     * Ensures that cache entries are invalid if the Bazel binary itself changes (e.g. updated
     * version or locally modified binary). Different Bazel versions may produce different analysis
     * graphs from the same source code.
     */
    private val blazeInstallMD5: com.google.common.hash.HashCode?

    private val blazeInstallMD5Fingerprint: ByteArray

    /**
     * The fingerprint of the [net.starlark.java.eval.StarlarkSemantics].
     * 
     * 
     * Starlark semantics affect the behavior of Starlark code, which in turn affects the analysis
     * graph.
     */
    private val starlarkSemanticsFingerprint: ByteArray

    /**
     * The version of the source code (workspace) being evaluated.
     * 
     * 
     * This corresponds to the state of the BUILD files, .bzl files, and source files. Any change
     * to the source code likely changes the analysis graph, so this version is critical for
     * correctness.
     */
    val evaluatingVersion: Long

    private val evaluatingVersionFingerprint: ByteArray

    /**
     * A distinguisher used to separate cache entries for different test cases or scenarios.
     * 
     * 
     * Allows integration tests to share a single cache backend without collision, or to force
     * specific cache keys for testing purposes.
     */
    private val distinguisherBytesForTesting: ByteArray

    /** Whether this invocations use fake data for stamping (volatile) information.  */
    val useFakeStampData: Boolean

    /**
     * The precomputed fingerprint of this node version.
     * 
     * 
     * This is the concatenation of the fingerprints of the other fields, providing a single hash
     * value for the entire version.
     */
    @kotlin.jvm.JvmField
    val precomputedFingerprint: ByteArray

    /**
     * A pointer to the specific workspace snapshot in the remote system.
     * 
     * 
     * This is NOT part of the cache key identity (hash/equals). It is used to retrieve
     * invalidation data or metadata associated with the specific state corresponding to [ ][.evaluatingVersion].
     */
    private val clientId: java.util.Optional<ClientId?>

    init {
        this.topLevelConfigChecksum = topLevelConfigChecksum
        this.topLevelConfigFingerprint = topLevelConfigChecksum.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        this.blazeInstallMD5 = blazeInstallMD5
        this.blazeInstallMD5Fingerprint = blazeInstallMD5.asBytes()
        this.starlarkSemanticsFingerprint = java.util.Objects.requireNonNull<ByteArray>(starlarkSemanticsFingerprint)
        this.evaluatingVersion = evaluatingVersion.getVal()
        this.evaluatingVersionFingerprint = com.google.common.primitives.Longs.toByteArray(evaluatingVersion.getVal())
        this.distinguisherBytesForTesting =
            distinguisherBytesForTesting.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        this.useFakeStampData = useFakeStampData
        this.precomputedFingerprint =
            com.google.common.hash.Hashing.sha256()
                .newHasher()
                .putInt(topLevelConfigFingerprint.size)
                .putBytes(topLevelConfigFingerprint)
                .putInt(blazeInstallMD5Fingerprint.size)
                .putBytes(blazeInstallMD5Fingerprint)
                .putInt(this.starlarkSemanticsFingerprint.size)
                .putBytes(this.starlarkSemanticsFingerprint)
                .putLong(this.evaluatingVersion)
                .putInt(this.distinguisherBytesForTesting.size)
                .putBytes(this.distinguisherBytesForTesting)
                .putBoolean(useFakeStampData)
                .hash()
                .asBytes()

        // This is undigested.
        this.clientId = java.util.Objects.requireNonNull<java.util.Optional<ClientId?>>(clientId)
    }

    /**
     * Returns the snapshot of the workspace.
     * 
     * 
     * Can be empty if snapshots are not supported by the workspace.
     */
    @Suppress("unused") // to be integrated
    fun getClientId(): java.util.Optional<ClientId?> {
        return clientId
    }

    fun concat(input: ByteArray?): ByteArray {
        return com.google.common.primitives.Bytes.concat(precomputedFingerprint, input)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("topLevelConfig", java.util.Arrays.hashCode(topLevelConfigFingerprint))
            .add("blazeInstall", java.util.Arrays.hashCode(blazeInstallMD5Fingerprint))
            .add("starlarkSemantics", java.util.Arrays.hashCode(starlarkSemanticsFingerprint))
            .add("evaluatingVersion", java.util.Arrays.hashCode(evaluatingVersionFingerprint))
            .add("distinguisherBytesForTesting", java.util.Arrays.hashCode(distinguisherBytesForTesting))
            .add("useFakeStampData", useFakeStampData)
            .add("precomputed", hashCode())
            .toString()
    }

    override fun hashCode(): Int {
        return java.util.Arrays.hashCode(precomputedFingerprint)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is FrontierNodeVersion) {
            return false
        }
        return java.util.Arrays.equals(precomputedFingerprint, obj.precomputedFingerprint)
    }

    fun getBlazeInstallMD5(): com.google.common.hash.HashCode? {
        return blazeInstallMD5
    }

    companion object {
        @kotlin.jvm.JvmField
        val CONSTANT_FOR_TESTING: FrontierNodeVersion = FrontierNodeVersion(
            "123",
            com.google.common.hash.HashCode.fromInt(42),
            byteArrayOf(1, 2, 3),
            IntVersion.of(9000),
            "distinguisher",  /* useFakeStampData= */
            true,
            java.util.Optional.of<ClientId?>(SnapshotClientId("for_testing", 123))
        )
    }
}
