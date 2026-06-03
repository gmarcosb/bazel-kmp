// Copyright 2009 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.util

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.util.TestIntegration
import java.util.Locale

/** TestIntegration represents an external link that is integrated with the test results.  */
class TestIntegration(
    private val contactEmail: String?,
    private val componentId: String?,
    private val name: String?,
    private val url: String?,
    private val iconUrl: String?,
    private val iconName: String?,
    private val description: String?,
    private val foregroundColor: String?,
    private val backgroundColor: String?
) {
    /** Represents each available field for TestIntegration.  */
    enum class ExternalLinkAttribute {
        NAME,
        URL,
        CONTACT_EMAIL,
        COMPONENT_ID,
        DESCRIPTION,
        ICON_NAME,
        ICON_URL,
        BACKGROUND_COLOR,
        FOREGROUND_COLOR;

        val xmlAttributeName: String?
            /** Gets the string representation of the current enum.  */
            get() = name.lowercase(Locale.getDefault())
    }

    // Group or user name responsible for this external integration.
    fun contactEmail(): String? {
        return contactEmail
    }

    // Component id (numeric) for this external integration.
    fun componentId(): String? {
        return componentId
    }

    // Display name of this external integration.
    fun name(): String? {
        return name
    }

    // URL that will display more data about this test result or integration.
    fun url(): String? {
        return url
    }

    // Optional: URL or name of the icon to be displayed.
    fun iconUrl(): String? {
        return iconUrl
    }

    fun iconName(): String? {
        return iconName
    }

    // Optional: Textual description that shows up as tooltip.
    fun description(): String? {
        return description
    }

    // Optional: Foreground color.
    fun foregroundColor(): String? {
        return foregroundColor
    }

    // Optional: Background color.
    fun backgroundColor(): String? {
        return backgroundColor
    }

    /** Builder is the builder class for TestIntegration  */
    class Builder private constructor() {
        private var contactEmail: String? = null
        private var componentId: String? = null
        private var name: String? = null
        private var url: String? = null
        private var iconUrl: String? = null
        private var iconName: String? = null
        private var description: String? = null
        private var foregroundColor: String? = null
        private var backgroundColor: String? = null

        /**
         * Sets the Contact Email value. The contact email is used for users to identify how to contact
         * the TestIntegration owner. This is optional.
         * 
         * @param email Email of the team responsible for this TestIntegration.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setContactEmail(email: String?): Builder {
            this.contactEmail = email
            return this
        }

        /**
         * Sets the component ID value, used to identify the tool that this TestIntegration belongs to.
         * This is optional.
         * 
         * @param id ID of the component.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setComponentId(id: String?): Builder {
            this.componentId = id
            return this
        }

        /**
         * Sets the name for the tool for this TestIntegration.
         * 
         * @param name Name of this TestIntegration.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setName(name: String?): Builder {
            this.name = name
            return this
        }

        /**
         * Sets the URL of this TestIntegration. It should be a FQDN, with optional url encoded
         * parameters.
         * 
         * @param url The location of the TestIntegration.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUrl(url: String?): Builder {
            this.url = url
            return this
        }

        /**
         * Sets the url of the icon. The icon should look good even if scaled down to 16x16. This is
         * optional; if not set, it will instead use the value passed to [.setIconName].
         * 
         * @param iconUrl Location of the icon.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIconUrl(iconUrl: String?): Builder {
            this.iconUrl = iconUrl
            return this
        }

        /**
         * Sets the name of the icon. This is optional; if not set it will instead use the value passed
         * to [.setIconUrl].
         * 
         * @param iconName name of the icon.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setIconName(iconName: String?): Builder {
            this.iconName = iconName
            return this
        }

        /**
         * Sets the description. The description is used to describe the TestIntegration object's
         * purpose. This is optional; if it isn't set, it will have a default value of `""`.
         * 
         * @param description The description for this TestIntegration.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDescription(description: String?): Builder {
            this.description = description
            return this
        }

        /**
         * Sets the foreground color of the TestIntegration link. This is optional; if it isn't set, the
         * link created will use the default foreground color per the tool's CSS.
         * 
         * @param foregroundColor The foreground color of the link, e.g. `"#000000"`.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setForegroundColor(foregroundColor: String?): Builder {
            this.foregroundColor = foregroundColor
            return this
        }

        /**
         * Sets the background color of the TestIntegration link. This is optional; if it isn't set, the
         * link created will use the default background color per the tool's CSS.
         * 
         * @param backgroundColor The background color of the link, e.g. `"#ffffff"`.
         * @return Builder
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBackgroundColor(backgroundColor: String?): Builder {
            this.backgroundColor = backgroundColor
            return this
        }

        /**
         * Builds a TestIntegration object.
         * @return Builder
         */
        fun build(): TestIntegration {
            return TestIntegration(
                contactEmail, componentId, name, url, iconUrl, iconName,
                description, foregroundColor, backgroundColor
            )
        }
    }

    val attributeValueMap: MutableMap<ExternalLinkAttribute?, String?>
        /*
            * getAttributeValueMap returns all of this TestIntegration's values in a Map.
            */
        get() {
            val map: MutableMap<ExternalLinkAttribute?, String?> =
                java.util.EnumMap<ExternalLinkAttribute?, String?>(ExternalLinkAttribute::class.java)
            map.put(ExternalLinkAttribute.NAME, name())
            map.put(ExternalLinkAttribute.URL, url())
            map.put(ExternalLinkAttribute.CONTACT_EMAIL, contactEmail())
            map.put(ExternalLinkAttribute.COMPONENT_ID, componentId())
            map.put(ExternalLinkAttribute.DESCRIPTION, description())
            map.put(ExternalLinkAttribute.ICON_NAME, iconName())
            map.put(ExternalLinkAttribute.ICON_URL, iconUrl())
            map.put(ExternalLinkAttribute.BACKGROUND_COLOR, backgroundColor())
            map.put(ExternalLinkAttribute.FOREGROUND_COLOR, foregroundColor())
            return map
        }

    companion object {
        fun builder(): Builder {
            return com.google.testing.junit.runner.util.TestIntegration.Builder()
                .setIconName("")
                .setIconUrl("")
                .setDescription("")
                .setForegroundColor("")
                .setBackgroundColor("")
        }
    }
}
