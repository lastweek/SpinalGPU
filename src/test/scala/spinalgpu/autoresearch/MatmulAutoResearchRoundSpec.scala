package spinalgpu.autoresearch

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MatmulAutoResearchRoundSpec extends AnyFunSuite with Matchers {
  test("tcgen05 phase attribution picks the largest bucket as the next focus") {
    val phaseCycles = Tcgen05StateCycles(
      collectCycles = 9,
      sharedCycles = 15,
      tensorCycles = 28,
      computeCycles = 41,
      packCycles = 12,
      respondCycles = 3
    )

    phaseCycles.totalCycles shouldBe 108
    phaseCycles.suggestedNextFocus shouldBe "tcgen05_compute"
  }

  test("round result JSON includes the required matmul autoresearch fields") {
    val result = MatmulAutoResearchRoundResult(
      shape = "32x32x32",
      cudaCoreCycles = 1300,
      tcgen05Cycles = 420,
      tcgen05StateCycles = Tcgen05StateCycles(
        collectCycles = 12,
        sharedCycles = 24,
        tensorCycles = 36,
        computeCycles = 48,
        packCycles = 8,
        respondCycles = 4
      ),
      correctnessPassed = true,
      fault = false,
      suggestedNextFocus = "tcgen05_compute"
    )

    val json = result.toJson

    json should include(""""shape":"32x32x32"""")
    json should include(""""cuda_core_cycles":1300""")
    json should include(""""tcgen05_cycles":420""")
    json should include(""""correctness_passed":true""")
    json should include(""""fault":false""")
    json should include(""""suggested_next_focus":"tcgen05_compute"""")
    json should include(""""tcgen05_state_cycles":{"collect_cycles":12,"shared_cycles":24,"tensor_cycles":36,"compute_cycles":48,"pack_cycles":8,"respond_cycles":4,"total_cycles":132}""")
  }
}
