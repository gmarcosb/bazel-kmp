// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

@RunWith(JUnit4::class)
class BulkTransferExceptionTest {
    @Test
    fun shouldProvideGenericMessageIfNoAddedException() {
        val bulkTransferException: BulkTransferException = BulkTransferException()
        assertThat(bulkTransferException.getMessage()).isEqualTo("Unknown error during bulk transfer")
    }

    @Test
    fun shouldPreserveMessageAsIsFromSingleException() {
        val bulkTransferException: BulkTransferException = BulkTransferException()
        bulkTransferException.add(IOException("Failure Type A"))
        assertThat(bulkTransferException.getMessage()).isEqualTo("Failure Type A")
    }

    @Test
    fun shouldSortAndRemoveDuplicatesWhenAggregatingMessages() {
        val bulkTransferException: BulkTransferException = BulkTransferException()
        bulkTransferException.add(IOException("Failure Type B"))
        bulkTransferException.add(IOException("Failure Type A"))
        bulkTransferException.add(IOException("Failure Type B"))
        assertThat(bulkTransferException.getMessage())
            .isEqualTo(
                "Multiple errors during bulk transfer:\n" + "Failure Type A\n" + "Failure Type B"
            )
    }

    @Test
    fun shouldProvideGenericMessageIfOnlyNullMessages() {
        val bulkTransferException: BulkTransferException = BulkTransferException()
        bulkTransferException.add(IOException())
        assertThat(bulkTransferException.getMessage()).isEqualTo("Unknown error during bulk transfer")
    }

    @Test
    fun shouldIgnoreNullMessagesWhenGettingMessage() {
        val bulkTransferException: BulkTransferException = BulkTransferException()
        bulkTransferException.add(IOException("Failure Type A"))
        bulkTransferException.add(IOException())
        assertThat(bulkTransferException.getMessage()).isEqualTo("Failure Type A")
    }
}
