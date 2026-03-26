package spinalgpu

import spinal.core._
import spinal.lib._

class CudaCoreArray(config: SmConfig) extends Component {
  val io = new Bundle {
    val issue = slave(Stream(CudaIssueReq(config)))
    val response = master(Stream(CudaIssueRsp(config)))
  }

  private val rspValid = RegInit(False)
  private val rspPayload = Reg(CudaIssueRsp(config))

  private def opResult(opcode: Bits, operandA: UInt, operandB: UInt): Bits = {
    val result = UInt(config.dataWidth bits)
    result := operandA

    switch(opcode) {
      is(B(Opcode.MOV, 8 bits)) {
        result := operandA
      }
      is(B(Opcode.MOVI, 8 bits)) {
        result := operandB
      }
      is(B(Opcode.ADD, 8 bits), B(Opcode.ADDI, 8 bits)) {
        result := operandA + operandB
      }
      is(B(Opcode.SUB, 8 bits)) {
        result := operandA - operandB
      }
      is(B(Opcode.MULLO, 8 bits)) {
        result := (operandA * operandB).resize(config.dataWidth)
      }
      is(B(Opcode.AND, 8 bits)) {
        result := operandA & operandB
      }
      is(B(Opcode.OR, 8 bits)) {
        result := operandA | operandB
      }
      is(B(Opcode.XOR, 8 bits)) {
        result := operandA ^ operandB
      }
      is(B(Opcode.SHL, 8 bits)) {
        result := operandA |<< operandB(log2Up(config.dataWidth) - 1 downto 0)
      }
      is(B(Opcode.SHR, 8 bits)) {
        result := operandA |>> operandB(log2Up(config.dataWidth) - 1 downto 0)
      }
      is(B(Opcode.SETEQ, 8 bits)) {
        result := U(operandA === operandB, config.dataWidth bits)
      }
      is(B(Opcode.SETLT, 8 bits)) {
        result := U(operandA.asSInt < operandB.asSInt, config.dataWidth bits)
      }
    }

    result.asBits
  }

  io.issue.ready := !rspValid || io.response.ready
  io.response.valid := rspValid
  io.response.payload := rspPayload

  when(io.issue.fire) {
    rspValid := True
    rspPayload.warpId := io.issue.payload.warpId
    rspPayload.completed := True
    for (lane <- 0 until config.warpSize) {
      rspPayload.result(lane) := B(0, config.dataWidth bits)
      when(io.issue.payload.activeMask(lane)) {
        rspPayload.result(lane) := opResult(
          io.issue.payload.opcode,
          io.issue.payload.operandA(lane),
          io.issue.payload.operandB(lane)
        )
      }
    }
  }

  when(io.response.fire) {
    rspValid := False
  }
}
