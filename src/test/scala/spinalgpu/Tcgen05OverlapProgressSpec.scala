package spinalgpu

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spinalgpu.toolchain.KernelCorpus

class Tcgen05OverlapProgressSpec extends AnyFunSuite with Matchers {
  private val config = GpuConfig(
    sm = SmConfig(
      subSmCount = 1,
      residentWarpsPerSubSm = 2,
      subSmIssueWidth = 32,
      sharedMemoryBytes = 1024,
      tensorMemoryBytesPerWarp = 512
    )
  )

  test("pending tcgen05 work does not block another warp in the same sub-SM from making forward progress") {
    KernelCorpusTestUtils.runStreamingMultiprocessorKernelCase(KernelCorpus.tcgen05OverlapProgress, config)
  }
}
