package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.ZungaViewModel
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun testViewModelInstantiation() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = ZungaViewModel(application)
    assertNotNull(viewModel)
  }
}
