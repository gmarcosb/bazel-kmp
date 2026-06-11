load("@rules_java//java:defs.bzl", "java_library")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

# Description:
#   A devtools-common options parser.
#   Open-sourced as part of Bazel.
package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"], allow_empty = True) + [
        "//src/main/java/com/google/devtools/common/options/processor:srcs",
        "//src/main/java/com/google/devtools/common/options/testing:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

kt_jvm_library(
    name = "invocation_policy",
    srcs = [
        "InvocationPolicyEnforcer.kt", "InvocationPolicyEnforcer.java",
        "InvocationPolicyParser.kt", "InvocationPolicyParser.java",
    ],
    deps = [
        ":options_internal",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

kt_jvm_library(
    name = "options",
    plugins = [
        "//src/main/java/com/google/devtools/common/options/processor:options_preprocessor",
        "//src/main/java/com/google/devtools/common/options/processor:options_class_processor",
    ],
    exports = [":options_internal"],
)

kt_jvm_library(
    name = "options_internal",
    srcs = glob(
        ["*.kt", "*.java"], allow_empty = True,
        exclude = [
            "HtmlUtils.kt", "HtmlUtils.java",
            "InvocationPolicyEnforcer.kt", "InvocationPolicyEnforcer.java",
            "InvocationPolicyParser.kt", "InvocationPolicyParser.java",
        ],
    ),
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skybridge:skybridge_interface",
        "//src/main/java/com/google/devtools/build/lib/util:pair",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/com/google/devtools/build/lib/util/regex:regex_util",
        "//src/main/java/net/starlark/java/spelling",
        "//third_party:auto_value",
        "//third_party:caffeine",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

kt_jvm_library(
    name = "html_utils",
    srcs = ["HtmlUtils.kt", "HtmlUtils.java"],
    deps = [
        ":options_internal",
        "//src/main/java/com/google/devtools/build/lib/util:markdown",
        "//third_party:guava",
    ],
)
