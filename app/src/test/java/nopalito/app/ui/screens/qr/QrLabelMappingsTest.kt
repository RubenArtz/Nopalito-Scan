/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.screens.qr

import nopalito.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block: 8F — QR label mappings.
 *
 * The persistent tokens (WiFi security and module shapes) are part of the
 * storage/backend contract and must never change; only the *display* is
 * localized through [wifiSecurityLabel] / [moduleShapeLabel]. Every known
 * token must map to a real, existing resource, and unknown/legacy tokens must
 * fall back to a defined resource instead of being rendered raw.
 */
class QrLabelMappingsTest {

    @Test
    fun wifiSecurityKnownTokensMapToDistinctResources() {
        val wpa = wifiSecurityLabel("WPA")
        val wep = wifiSecurityLabel("WEP")
        assertTrue("WPA resource exists", wpa != 0)
        assertTrue("WEP resource exists", wep != 0)
        assertTrue("WPA and WEP map to different labels", wpa != wep)
    }

    @Test
    fun wifiSecurityOpenVariantsMapToOpenLabel() {
        val open = wifiSecurityLabel("Abierta")
        assertEquals("legacy Spanish token", open, wifiSecurityLabel("Abierta"))
        assertEquals("Open variant", open, wifiSecurityLabel("Open"))
        assertEquals("nopass variant", open, wifiSecurityLabel("nopass"))
        assertEquals("None variant", open, wifiSecurityLabel("None"))
        assertEquals("null falls back to open", open, wifiSecurityLabel(null))
        assertEquals("unknown token falls back to open", open, wifiSecurityLabel("WPA2"))
    }

    @Test
    fun wifiSecurityTokensNeverRenderedRaw() {
        // The mapped resources are localized text, never the raw token itself.
        val open = wifiSecurityLabel(null)
        assertEquals("fallback must be the open label resource", R.string.qr_wifi_security_open, open)
    }

    @Test
    fun moduleShapeKnownTokensMapToDistinctResources() {
        val square = moduleShapeLabel("square")
        val rounded = moduleShapeLabel("rounded")
        val circle = moduleShapeLabel("circle")
        val diamond = moduleShapeLabel("diamond")
        assertEquals(square, R.string.qr_shape_square)
        assertEquals(rounded, R.string.qr_shape_rounded)
        assertEquals(circle, R.string.qr_shape_circle)
        assertEquals(diamond, R.string.qr_shape_diamond)
    }

    @Test
    fun moduleShapeUnknownTokenFallsBackToSquare() {
        assertEquals(R.string.qr_shape_square, moduleShapeLabel("star"))
    }

    @Test
    fun shapeAndSecurityLabelSpacesAreDisjoint() {
        // Guards against accidentally reusing a label across domains.
        val shapeRes = listOf(
            R.string.qr_shape_square,
            R.string.qr_shape_rounded,
            R.string.qr_shape_circle,
            R.string.qr_shape_diamond,
        )
        val securityRes = listOf(
            R.string.qr_wifi_security_wpa,
            R.string.qr_wifi_security_wep,
            R.string.qr_wifi_security_open,
        )
        assertTrue(shapeRes.toSet().intersect(securityRes.toSet()).isEmpty())
    }
}