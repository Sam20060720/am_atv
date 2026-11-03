package dev.amatv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
@LargeTest
class TvNavigationBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @get:Rule val failures = UiFailureArtifacts()

    @Test fun warmLaunch() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = configuredBenchmarkIterations(),
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        TvUi(device).apply { browseTab("home"); id("browse-home"); settle(700) }
    }

    @Test fun homeHorizontalNavigation() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = configuredBenchmarkIterations(),
        setupBlock = { startActivityAndWait(); TvUi(device).enterHomeShelf("app_top_albums") },
    ) {
        TvUi(device).apply {
            repeat(10) { right(100) }
            settle(350)
            repeat(10) { left(100) }
            settle(350)
            homeCard()
        }
    }

    @Test fun homeVerticalNavigation() = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        iterations = configuredBenchmarkIterations(),
        setupBlock = { startActivityAndWait(); TvUi(device).enterHomeFirstCard() },
    ) {
        TvUi(device).apply {
            val steps = minOf(4, homeRowCount() - 1)
            repeat(steps) { down(380) }
            repeat(steps) { up(380) }
            homeCard()
        }
    }
}
