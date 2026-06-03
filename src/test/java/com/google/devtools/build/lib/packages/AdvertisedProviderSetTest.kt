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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Unit tests for [AdvertisedProviderSet].  */
@RunWith(JUnit4::class)
class AdvertisedProviderSetTest {
    @org.junit.Test
    fun fingerprintsMatchExactly() {
        // Demonstrates that fingerprints of some choice AdvertisedProviderSet instances are exactly we
        // expect them to be.
        //
        // If this test fails because the implementation of AdvertisedProviderSet#fingerprint has been
        // intentionally changed in a manner compatible with the properties listed in that method's
        // javadoc, then simply update the expectations here.

        Truth.assertThat(getFingerprint(AdvertisedProviderSet.ANY))
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a"
                )
            )
        Truth.assertThat(getFingerprint(AdvertisedProviderSet.EMPTY))
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"
                )
            )
        Truth.assertThat(getFingerprint(AdvertisedProviderSet.builder().addBuiltin(String::class.java).build()))
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "d93c0c71b65c3af6dd7fb823680bb768ede3857abb3046ebb4762f5a3e8793dc"
                )
            )
        Truth.assertThat(getFingerprint(AdvertisedProviderSet.builder().addBuiltin(Int::class.java).build()))
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "979d311543b348225618bd436c874e24cfe4b7a35c891cbfaf89ce69885981b4"
                )
            )
        Truth.assertThat(
            getFingerprint(
                AdvertisedProviderSet.builder()
                    .addStarlark(
                        StarlarkProviderIdentifier.forKey(
                            Key(
                                keyForBuild(Label.parseCanonicalUnchecked("//my:label1.bzl")),
                                "exportedName1"
                            )
                        )
                    )
                    .build()
            )
        )
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "a17a99d19f39ae61e927da9169d42274b7121fa0fbc79bbb85cce7b199576a42"
                )
            )
        Truth.assertThat(
            getFingerprint(
                AdvertisedProviderSet.builder()
                    .addStarlark(
                        StarlarkProviderIdentifier.forKey(
                            Key(
                                keyForBuild(Label.parseCanonicalUnchecked("//my:label1.bzl")),
                                "exportedName1"
                            )
                        )
                    )
                    .addStarlark(
                        StarlarkProviderIdentifier.forKey(
                            Key(
                                keyForBuild(Label.parseCanonicalUnchecked("//my:label2.bzl")),
                                "exportedName2"
                            )
                        )
                    )
                    .build()
            )
        )
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "2909013264231d50a3550d41e2eaf0fbd501bfffacf6303019cc8a93e187bf68"
                )
            )
        Truth.assertThat(
            getFingerprint(
                AdvertisedProviderSet.builder()
                    .addStarlark(
                        StarlarkProviderIdentifier.forKey(
                            Key(
                                keyForBuild(Label.parseCanonicalUnchecked("//my:label2.bzl")),
                                "exportedName2"
                            )
                        )
                    )
                    .build()
            )
        )
            .isEqualTo(
                com.google.common.hash.HashCode.fromString(
                    "4d5efc81e801feaeef6b5549d2c4b9d943ae673c76456f85631dc61cae698e4c"
                )
            )
    }

    companion object {
        private fun getFingerprint(advertisedProviderSet: AdvertisedProviderSet): com.google.common.hash.HashCode {
            val fp: Fingerprint = Fingerprint()
            advertisedProviderSet.fingerprint(fp)
            return com.google.common.hash.HashCode.fromBytes(fp.digestAndReset())
        }
    }
}
