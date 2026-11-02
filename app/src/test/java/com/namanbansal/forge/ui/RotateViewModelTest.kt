package com.namanbansal.forge.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.namanbansal.forge.tools.pdf.RotateViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotateViewModelTest {
    private val vm = RotateViewModel(ApplicationProvider.getApplicationContext<Application>(), SavedStateHandle())

    @Test fun rotationsAccumulateOnSelectionAndCancelOut() {
        // Nothing selected before a PDF is open, so nothing changes.
        vm.rotateSelected(90)
        assertEquals(0, vm.changedCount())
        vm.togglePage(2)
        vm.rotateSelected(90); vm.rotateSelected(90)
        assertEquals(mapOf(2 to 180), vm.rotations.value)
        vm.rotateSelected(-180)
        assertEquals(emptyMap<Int, Int>(), vm.rotations.value)
        vm.rotateSelected(270); vm.rotateSelected(90) // 360: net zero but raw value kept so the preview does not spin back
        assertEquals(0, vm.changedCount())
        vm.resetRotations()
        assertEquals(emptyMap<Int, Int>(), vm.rotations.value)
    }

    @Test fun togglingSelection() {
        vm.togglePage(1); vm.togglePage(3)
        assertEquals(setOf(1, 3), vm.selected.value)
        vm.togglePage(1)
        assertEquals(setOf(3), vm.selected.value)
        vm.selectNone()
        assertEquals(emptySet<Int>(), vm.selected.value)
    }
}
