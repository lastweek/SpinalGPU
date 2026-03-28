package spinalgpu

import spinal.core._
import spinal.lib._

class CudaCoreArray(config: SmConfig) extends Component {
  private object State extends SpinalEnum {
    val IDLE, WAIT_SLICE = newElement()
  }

  val io = new Bundle {
    val issue = slave(Stream(CudaIssueReq(config)))
    val response = master(Stream(CudaIssueRsp(config)))
  }

  private val state = RegInit(State.IDLE)
  private val pending = Reg(CudaIssueReq(config))
  private val rspValid = RegInit(False)
  private val rspPayload = Reg(CudaIssueRsp(config))
  private val latencyCounter = Reg(
    UInt(
      log2Up(
        (
          config.fpFmaLatency max
            config.fpMulLatency max
            config.fpAddLatency max
            config.cudaIntegerLatency max
            config.fp16ScalarLatency max
            config.fp16x2Latency max
            config.fp8ConvertLatency
        ) + 1
      ) bits
    )
  ) init (0)
  private val subwarpBase = Reg(UInt(log2Up(config.warpSize + 1) bits)) init (0)
  private val resultBuffer = Vec.fill(config.warpSize)(Reg(Bits(config.dataWidth bits)) init (0))

  private def opLatency(opcode: Bits): UInt = {
    val latency = UInt(latencyCounter.getWidth bits)
    latency := config.cudaIntegerLatency
    switch(opcode) {
      is(B(Opcode.FADD, 8 bits)) {
        latency := config.fpAddLatency
      }
      is(B(Opcode.FSUB, 8 bits), B(Opcode.FABS, 8 bits), B(Opcode.FNEG, 8 bits), B(Opcode.FSETEQ, 8 bits), B(Opcode.FSETLT, 8 bits)) {
        latency := config.fpAddLatency
      }
      is(B(Opcode.FMUL, 8 bits)) {
        latency := config.fpMulLatency
      }
      is(B(Opcode.FFMA, 8 bits)) {
        latency := config.fpFmaLatency
      }
      is(B(Opcode.HADD, 8 bits), B(Opcode.HMUL, 8 bits), B(Opcode.HFMA, 8 bits), B(Opcode.CVTF32F16, 8 bits), B(Opcode.CVTF16F32, 8 bits)) {
        latency := config.fp16ScalarLatency
      }
      is(B(Opcode.HADD2, 8 bits), B(Opcode.HMUL2, 8 bits)) {
        latency := config.fp16x2Latency
      }
      is(B(Opcode.CVTF16X2E4M3X2, 8 bits), B(Opcode.CVTF16X2E5M2X2, 8 bits), B(Opcode.CVTE4M3X2F16X2, 8 bits), B(Opcode.CVTE5M2X2F16X2, 8 bits)) {
        latency := config.fp8ConvertLatency
      }
    }
    latency
  }

  private def opResult(opcode: Bits, operandA: Bits, operandB: Bits, operandC: Bits): Bits = {
    val operandAUInt = operandA.asUInt
    val operandBUInt = operandB.asUInt
    val result = UInt(config.dataWidth bits)
    result := operandAUInt

    switch(opcode) {
      is(B(Opcode.MOV, 8 bits)) {
        result := operandAUInt
      }
      is(B(Opcode.MOVI, 8 bits)) {
        result := operandBUInt
      }
      is(B(Opcode.ADD, 8 bits), B(Opcode.ADDI, 8 bits)) {
        result := operandAUInt + operandBUInt
      }
      is(B(Opcode.SUB, 8 bits)) {
        result := operandAUInt - operandBUInt
      }
      is(B(Opcode.MULLO, 8 bits)) {
        result := (operandAUInt * operandBUInt).resize(config.dataWidth)
      }
      is(B(Opcode.AND, 8 bits)) {
        result := operandAUInt & operandBUInt
      }
      is(B(Opcode.OR, 8 bits)) {
        result := operandAUInt | operandBUInt
      }
      is(B(Opcode.XOR, 8 bits)) {
        result := operandAUInt ^ operandBUInt
      }
      is(B(Opcode.SHL, 8 bits)) {
        result := operandAUInt |<< operandBUInt(log2Up(config.dataWidth) - 1 downto 0)
      }
      is(B(Opcode.SHR, 8 bits)) {
        result := operandAUInt |>> operandBUInt(log2Up(config.dataWidth) - 1 downto 0)
      }
      is(B(Opcode.SETEQ, 8 bits)) {
        result := U(operandAUInt === operandBUInt, config.dataWidth bits)
      }
      is(B(Opcode.SETLT, 8 bits)) {
        result := U(operandAUInt < operandBUInt, config.dataWidth bits)
      }
      is(B(Opcode.SETLTS, 8 bits)) {
        result := U(operandA.asSInt < operandB.asSInt, config.dataWidth bits)
      }
      is(B(Opcode.FADD, 8 bits)) {
        result := Fp32Math.add(operandA, operandB).asUInt
      }
      is(B(Opcode.FSUB, 8 bits)) {
        result := Fp32Math.sub(operandA, operandB).asUInt
      }
      is(B(Opcode.FMUL, 8 bits)) {
        result := Fp32Math.mul(operandA, operandB).asUInt
      }
      is(B(Opcode.FFMA, 8 bits)) {
        result := Fp32Math.fma(operandA, operandB, operandC).asUInt
      }
      is(B(Opcode.FABS, 8 bits)) {
        result := Fp32Math.abs(operandA).asUInt
      }
      is(B(Opcode.FNEG, 8 bits)) {
        result := Fp32Math.neg(operandA).asUInt
      }
      is(B(Opcode.FSETEQ, 8 bits)) {
        result := U(Fp32Math.eq(operandA, operandB), config.dataWidth bits)
      }
      is(B(Opcode.FSETLT, 8 bits)) {
        result := U(Fp32Math.lt(operandA, operandB), config.dataWidth bits)
      }
      is(B(Opcode.SEL, 8 bits)) {
        result := Mux(operandC.orR, operandAUInt, operandBUInt)
      }
      is(B(Opcode.HADD, 8 bits)) {
        result := Fp16Math.add(operandA(15 downto 0), operandB(15 downto 0)).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.HMUL, 8 bits)) {
        result := Fp16Math.mul(operandA(15 downto 0), operandB(15 downto 0)).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.HFMA, 8 bits)) {
        result := Fp16Math.fma(operandA(15 downto 0), operandB(15 downto 0), operandC(15 downto 0)).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.HADD2, 8 bits)) {
        result := Fp16Math.add2(operandA, operandB).asUInt
      }
      is(B(Opcode.HMUL2, 8 bits)) {
        result := Fp16Math.mul2(operandA, operandB).asUInt
      }
      is(B(Opcode.CVTF32F16, 8 bits)) {
        result := Fp16Math.toFp32(operandA(15 downto 0)).asUInt
      }
      is(B(Opcode.CVTF16F32, 8 bits)) {
        result := Fp16Math.fromFp32(operandA).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.CVTF16X2E4M3X2, 8 bits)) {
        result := Fp8Format.e4m3x2ToF16x2(operandA(15 downto 0)).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.CVTF16X2E5M2X2, 8 bits)) {
        result := Fp8Format.e5m2x2ToF16x2(operandA(15 downto 0)).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.CVTE4M3X2F16X2, 8 bits)) {
        result := Fp8Format.f16x2ToE4m3x2SatFinite(operandA).asUInt.resize(config.dataWidth)
      }
      is(B(Opcode.CVTE5M2X2F16X2, 8 bits)) {
        result := Fp8Format.f16x2ToE5m2x2SatFinite(operandA).asUInt.resize(config.dataWidth)
      }
    }

    result.asBits
  }

  io.issue.ready := state === State.IDLE && !rspValid
  io.response.valid := rspValid
  io.response.payload := rspPayload

  when(io.issue.fire) {
    for (lane <- 0 until config.warpSize) {
      resultBuffer(lane) := B(0, config.dataWidth bits)
    }
    pending := io.issue.payload
    rspPayload.warpId := io.issue.payload.warpId
    rspPayload.completed := True
    subwarpBase := 0
    latencyCounter := opLatency(io.issue.payload.opcode) - 1
    state := State.WAIT_SLICE
  }

  when(state === State.WAIT_SLICE) {
    when(latencyCounter === 0) {
      val finishingWarp = subwarpBase + config.cudaLaneCount >= config.warpSize

      when(finishingWarp) {
        rspPayload.warpId := pending.warpId
        rspPayload.completed := True
        for (lane <- 0 until config.warpSize) {
          rspPayload.result(lane) := resultBuffer(lane)
        }
      }

      for (laneOffset <- 0 until config.cudaLaneCount) {
        val laneIndex = (subwarpBase + U(laneOffset, subwarpBase.getWidth bits)).resized
        when(laneIndex < config.warpSize && pending.activeMask(laneIndex)) {
          val laneResult = opResult(
            pending.opcode,
            pending.operandA(laneIndex),
            pending.operandB(laneIndex),
            pending.operandC(laneIndex)
          )
          resultBuffer(laneIndex) := laneResult
          when(finishingWarp) {
            rspPayload.result(laneIndex) := laneResult
          }
        }
      }

      when(finishingWarp) {
        rspValid := True
        state := State.IDLE
      } otherwise {
        subwarpBase := subwarpBase + config.cudaLaneCount
        latencyCounter := opLatency(pending.opcode) - 1
      }
    } otherwise {
      latencyCounter := latencyCounter - 1
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
