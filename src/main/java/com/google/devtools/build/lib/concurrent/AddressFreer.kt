// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.devtools.build.lib.unsafe.UnsafeProvider

/** Used to cleanup memory allocated by [Unsafe.allocateMemory] using [Cleaner].  */
internal  // TODO: b/359688989 - clean this up
class AddressFreer(private val address: Long) : java.lang.Runnable {
    // TODO: b/386384684 - remove Unsafe usage
    override fun run() {
        UnsafeProvider.unsafe().freeMemory(address)
    }
}
