package spinalgpu

import spinal.core._
import spinal.lib._

object ExecutionUnitKind extends SpinalEnum {
  val CONTROL, CUDA, LSU, SFU, TENSOR = newElement()
}

object AddressSpaceKind extends SpinalEnum {
  val SHARED, GLOBAL = newElement()
}

case class KernelCommandDesc(config: SmConfig) extends Bundle {
  val entryPc = UInt(config.addressWidth bits)
  val gridDimX = UInt(config.dataWidth bits)
  val gridDimY = UInt(config.dataWidth bits)
  val gridDimZ = UInt(config.dataWidth bits)
  val blockDimX = UInt(config.threadCountWidth bits)
  val blockDimY = UInt(config.threadCountWidth bits)
  val blockDimZ = UInt(config.threadCountWidth bits)
  val argBase = UInt(config.addressWidth bits)
  val sharedBytes = UInt(config.sharedBytesWidth bits)
}

case class WarpContext(config: SmConfig) extends Bundle {
  val valid = Bool()
  val runnable = Bool()
  val pc = UInt(config.addressWidth bits)
  val activeMask = Bits(config.warpSize bits)
  val threadBase = UInt(config.threadCountWidth bits)
  val threadBaseX = UInt(config.threadCountWidth bits)
  val threadBaseY = UInt(config.threadCountWidth bits)
  val threadBaseZ = UInt(config.threadCountWidth bits)
  val threadCount = UInt(config.threadCountWidth bits)
  val outstanding = Bool()
  val exited = Bool()
  val faulted = Bool()
}

case class WarpContextWrite(config: SmConfig) extends Bundle {
  val index = UInt(config.warpIdWidth bits)
  val context = WarpContext(config)
}

case class WarpBindingInfo(config: SmConfig) extends Bundle {
  val bound = Bool()
  val subSmId = UInt(config.subSmIdWidth bits)
  val localSlotId = UInt(config.localSlotIdWidth bits)
}

case class WarpScheduleReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val context = WarpContext(config)
}

case class SubSmBindReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val subSmId = UInt(config.subSmIdWidth bits)
  val localSlotId = UInt(config.localSlotIdWidth bits)
}

case class SubSmScheduleReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val localSlotId = UInt(config.localSlotIdWidth bits)
  val context = WarpContext(config)
}

case class FetchReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val pc = UInt(config.addressWidth bits)
}

case class FetchRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val pc = UInt(config.addressWidth bits)
  val instruction = Bits(config.instructionWidth bits)
  val fault = Bool()
  val faultCode = UInt(config.faultCodeWidth bits)
}

case class FetchMemReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val address = UInt(config.addressWidth bits)
}

case class FetchMemRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val error = Bool()
  val readData = Bits(config.dataWidth bits)
}

case class DecodedInstruction(config: SmConfig) extends Bundle {
  val valid = Bool()
  val illegal = Bool()
  val opcode = Bits(8 bits)
  val target = ExecutionUnitKind()
  val rd = UInt(config.registerAddressWidth bits)
  val rs0 = UInt(config.registerAddressWidth bits)
  val rs1 = UInt(config.registerAddressWidth bits)
  val rs2 = UInt(config.registerAddressWidth bits)
  val immediate = SInt(config.dataWidth bits)
  val specialRegister = UInt(config.specialRegisterWidth bits)
  val addressSpace = AddressSpaceKind()
  val writesRd = Bool()
  val usesRs0 = Bool()
  val usesRs1 = Bool()
  val usesRs2 = Bool()
  val isStore = Bool()
  val isLoad = Bool()
  val isBranch = Bool()
  val branchOnZero = Bool()
  val isExit = Bool()
  val isTrap = Bool()
  val isS2r = Bool()
}

case class WritebackPacket(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val rd = UInt(config.registerAddressWidth bits)
  val writeMask = Bits(config.warpSize bits)
  val data = Vec(Bits(config.dataWidth bits), config.warpSize)
}

case class TrapInfo(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val pc = UInt(config.addressWidth bits)
  val faultCode = UInt(config.faultCodeWidth bits)
}

case class CudaIssueReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val opcode = Bits(8 bits)
  val activeMask = Bits(config.warpSize bits)
  val operandA = Vec(Bits(config.dataWidth bits), config.warpSize)
  val operandB = Vec(Bits(config.dataWidth bits), config.warpSize)
  val operandC = Vec(Bits(config.dataWidth bits), config.warpSize)
}

case class CudaIssueRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val result = Vec(Bits(config.dataWidth bits), config.warpSize)
}

case class LsuReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val addressSpace = AddressSpaceKind()
  val write = Bool()
  val activeMask = Bits(config.warpSize bits)
  val addresses = Vec(UInt(config.addressWidth bits), config.warpSize)
  val writeData = Vec(Bits(config.dataWidth bits), config.warpSize)
  val byteMask = Bits(config.byteMaskWidth bits)
}

case class LsuRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val error = Bool()
  val faultCode = UInt(config.faultCodeWidth bits)
  val faultAddress = UInt(config.addressWidth bits)
  val readData = Vec(Bits(config.dataWidth bits), config.warpSize)
}

case class SfuReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val opcode = Bits(8 bits)
  val activeMask = Bits(config.warpSize bits)
  val operand = Vec(UInt(config.dataWidth bits), config.warpSize)
}

case class SfuRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val result = Vec(Bits(config.dataWidth bits), config.warpSize)
}

case class TensorReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val opcode = Bits(8 bits)
  val activeMask = Bits(config.warpSize bits)
  val operandA = Vec(UInt(config.dataWidth bits), config.warpSize)
  val operandB = Vec(UInt(config.dataWidth bits), config.warpSize)
}

case class TensorRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val result = Vec(Bits(config.dataWidth bits), config.warpSize)
}

case class SharedMemReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val write = Bool()
  val address = UInt(config.sharedAddressWidth bits)
  val writeData = Bits(config.dataWidth bits)
  val byteMask = Bits(config.byteMaskWidth bits)
}

case class SharedMemRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val error = Bool()
  val readData = Bits(config.dataWidth bits)
  val bankIndex = UInt(config.sharedBankIndexWidth bits)
}

case class GlobalMemBurstReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val write = Bool()
  val address = UInt(config.addressWidth bits)
  val beatCount = UInt(config.globalBurstBeatCountWidth bits)
  val writeData = Vec(Bits(config.dataWidth bits), config.cudaLaneCount)
  val byteMask = Bits(config.byteMaskWidth bits)
}

case class GlobalMemBurstRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val error = Bool()
  val beatCount = UInt(config.globalBurstBeatCountWidth bits)
  val readData = Vec(Bits(config.dataWidth bits), config.cudaLaneCount)
}

case class ExternalMemBurstReq(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val write = Bool()
  val address = UInt(config.addressWidth bits)
  val beatCount = UInt(config.globalBurstBeatCountWidth bits)
  val writeData = Vec(Bits(config.dataWidth bits), config.cudaLaneCount)
  val byteMask = Bits(config.byteMaskWidth bits)
}

case class ExternalMemBurstRsp(config: SmConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth bits)
  val completed = Bool()
  val error = Bool()
  val beatCount = UInt(config.globalBurstBeatCountWidth bits)
  val readData = Vec(Bits(config.dataWidth bits), config.cudaLaneCount)
}

case class KernelExecutionStatus(config: SmConfig) extends Bundle {
  val busy = Bool()
  val done = Bool()
  val fault = Bool()
  val faultPc = UInt(config.addressWidth bits)
  val faultCode = UInt(config.faultCodeWidth bits)
}

case class SharedMemoryClearIo(config: SmConfig) extends Bundle with IMasterSlave {
  val start = Bool()
  val busy = Bool()

  override def asMaster(): Unit = {
    out(start)
    in(busy)
  }
}
