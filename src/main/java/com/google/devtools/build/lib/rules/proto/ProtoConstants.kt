// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.proto

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.cmdline.Label

/** Constants used in Proto rules.  */
object ProtoConstants {
    // Two keys support either bzlmod or WORKSPACE mode of cc_shared_library
    @kotlin.jvm.JvmField
    val EXTERNAL_PROTO_INFO_KEYS: ImmutableList<BzlLoadValue.Key?> = ImmutableList.of<BzlLoadValue.Key?>(
        BzlLoadValue.keyForBuild( // WORKSPACE
            Label.parseCanonicalUnchecked(
                "@com_google_protobuf//bazel/private:proto_info.bzl"
            )
        ),
        BzlLoadValue.keyForBuild( // tests
            Label.parseCanonicalUnchecked(
                "@com_google_protobuf+//bazel/private:proto_info.bzl"
            )
        ),
        BzlLoadValue.keyForBuild( // bzlmod
            Label.parseCanonicalUnchecked(
                "@@protobuf+//bazel/private:proto_info.bzl"
            )
        )
    )

    @kotlin.jvm.JvmField
    val PROTO_LANG_TOOLCHAIN_INFO: BzlLoadValue.Key? =
        BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked("@@com_google_protobuf+//bazel/common:proto_lang_toolchain_info.bzl"))
    // The flags need to point to @bazel_tools, because this is a canonical repo
    // name when either bzlmod or WORKSPACE mode is used.
    /** Default label for proto compiler. */
    const val DEFAULT_PROTOC_LABEL: String = "@bazel_tools//tools/proto:protoc"

    /** Default label for java proto toolchains.  */
    const val DEFAULT_JAVA_PROTO_LABEL: String = "@bazel_tools//tools/proto:java_toolchain"

    /** Default label for java lite proto toolchains.  */
    const val DEFAULT_JAVA_LITE_PROTO_LABEL: String = "@bazel_tools//tools/proto:javalite_toolchain"

    /** Default label for cc proto toolchains.  */
    const val DEFAULT_CC_PROTO_LABEL: String = "@bazel_tools//tools/proto:cc_toolchain"

    /** Default label for j2objc proto toolchains.  */
    const val DEFAULT_J2OBJC_PROTO_LABEL: String = "@bazel_tools//tools/j2objc:j2objc_proto_toolchain"

    /**
     * This constant is used in ProtoCompileActionBuilder to generate an error message that's
     * displayed when a strict proto deps violation occurs.
     * 
     * 
     * %1$s is replaced with the label of the proto_library rule that's currently being built.
     * 
     * 
     * %%s is replaced with the literal "%s", which is passed to the proto-compiler, which replaces
     * it with the .proto file that violates strict proto deps.
     */
    const val STRICT_PROTO_DEPS_VIOLATION_MESSAGE: String =
        "--direct_dependencies_violation_msg=%%s is imported, but %1\$s doesn't directly depend on a proto_library that 'srcs' it."
}
